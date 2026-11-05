package com.kangheng.healthos.controller.patient;

import jakarta.validation.Valid;

import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.contract.patient.PatientContract;
import com.kangheng.healthos.service.patient.PatientOrphanService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者域维护接口：孤儿对象扫描与定点清理。
 *
 * <p>扫描接口是只读的；清理接口必须显式给出对象 key，
 * 不提供"清理全部孤儿"的快捷方式——孤儿是推断出来的，批量删文件不能靠推断。
 *
 * <p>这两个接口限定运维账号：孤儿的归属是推断出来的（对象存储里有文件、
 * 但患者表里没有对应记录），无法按 owner 过滤，只能由运维角色执行。
 */
@RestController
@RequestMapping("/api/v1/patients/maintenance")
public class PatientMaintenanceController {

    private final PatientOrphanService orphanService;
    private final AccessControl accessControl;

    public PatientMaintenanceController(PatientOrphanService orphanService, AccessControl accessControl) {
        this.orphanService = orphanService;
        this.accessControl = accessControl;
    }

    @GetMapping("/orphans")
    public PatientContract.OrphanScan orphans(@CurrentUser String username) {
        accessControl.requireAdmin(username);
        return orphanService.scan();
    }

    @PostMapping("/orphans/purge")
    public PatientContract.OrphanPurgeResult purge(@CurrentUser String username,
                                                   @Valid @RequestBody PatientContract.OrphanPurgeRequest request) {
        accessControl.requireAdmin(username);
        return orphanService.purge(request.keys());
    }
}
