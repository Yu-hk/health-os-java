import java.sql.*;
import java.util.*;

public class fix_care_snapshot_status {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:55433/healthos";
        String user = "healthos", pass = "healthos";
        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            // 找出 care_patient_state 当前指向的已发布修订
            String sql = "SELECT patient_id, active_report_id, active_report_revision, "
                    + "active_plan_id, active_plan_revision FROM care_patient_state";
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    fixRev(c, rs.getString("active_report_id"), rs.getInt("active_report_revision"), "SIGNED");
                    fixRev(c, rs.getString("active_plan_id"), rs.getInt("active_plan_revision"), "ACTIVE");
                }
            }
            System.out.println("done. scanned=" + scanned + " fixed=" + fixed);
        }
    }

    static int scanned = 0, fixed = 0;

    static void fixRev(Connection c, String docId, int rev, String expected) throws Exception {
        if (docId == null || rev == 0) return;
        String sel = "SELECT id, snapshot FROM care_revision WHERE document_id=? AND revision=?";
        try (PreparedStatement ps = c.prepareStatement(sel)) {
            ps.setString(1, docId);
            ps.setInt(2, rev);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) { System.out.println("  no revision doc=" + docId + " rev=" + rev); return; }
                String snap = r.getString("snapshot");
                String id = r.getString("id");
                if (snap == null || !snap.contains("\"status\"")) { System.out.println("  no status field doc=" + docId); return; }
                String cur = statusOf(snap);
                scanned++;
                if (expected.equals(cur)) { System.out.println("  OK doc=" + docId + " rev=" + rev + " status=" + cur); return; }
                String fixedSnap = setStatus(snap, expected);
                String upd = "UPDATE care_revision SET snapshot=? WHERE id=?";
                try (PreparedStatement u = c.prepareStatement(upd)) {
                    u.setString(1, fixedSnap);
                    u.setString(2, id);
                    u.executeUpdate();
                }
                // 同步回写 care_document.aggregate（若存在且状态不一致）
                String sel2 = "SELECT aggregate FROM care_document WHERE id=?";
                try (PreparedStatement p2 = c.prepareStatement(sel2)) {
                    p2.setString(1, docId);
                    try (ResultSet r2 = p2.executeQuery()) {
                        if (r2.next()) {
                            String agg = r2.getString("aggregate");
                            if (agg != null && !expected.equals(statusOf(agg))) {
                                String fixedAgg = setStatus(agg, expected);
                                try (PreparedStatement u2 = c.prepareStatement("UPDATE care_document SET aggregate=? WHERE id=?")) {
                                    u2.setString(1, fixedAgg);
                                    u2.setString(2, docId);
                                    u2.executeUpdate();
                                }
                            }
                        }
                    }
                }
                fixed++;
                System.out.println("  FIXED doc=" + docId + " rev=" + rev + " " + cur + " -> " + expected);
            }
        }
    }

    static String statusOf(String json) {
        int i = json.indexOf("\"status\"");
        if (i < 0) return null;
        int c = json.indexOf(':', i);
        int q1 = json.indexOf('"', c);
        int q2 = json.indexOf('"', q1 + 1);
        return json.substring(q1 + 1, q2);
    }

    static String setStatus(String json, String status) {
        int i = json.indexOf("\"status\"");
        int c = json.indexOf(':', i);
        int q1 = json.indexOf('"', c);
        int q2 = json.indexOf('"', q1 + 1);
        return json.substring(0, q1 + 1) + status + json.substring(q2);
    }
}
