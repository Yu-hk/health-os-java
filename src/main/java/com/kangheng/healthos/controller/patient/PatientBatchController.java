package com.kangheng.healthos.controller.patient;

import java.io.IOException;

import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.contract.patient.PatientContract;
import com.kangheng.healthos.service.patient.PatientBatchService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 批量导入接口。
 *
 * <p>提交只做两件事：解析成行、写一条 outbox 事件。真正的导入在
 * {@code PatientBatchImportHandler} 里异步跑，所以 5000 行也不会让请求超时。
 */
@RestController
@RequestMapping("/api/v1/patients/batches")
public class PatientBatchController {

    private final PatientBatchService batchService;

    public PatientBatchController(PatientBatchService batchService) {
        this.batchService = batchService;
    }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PatientContract.BatchSubmitResponse submit(@CurrentUser String username,
                                                      @RequestParam("file") MultipartFile file) {
        try {
            return batchService.submit(file.getOriginalFilename(), file.getBytes(), username);
        } catch (IOException ex) {
            throw BizException.badRequest("读取批次文件失败: " + ex.getMessage());
        }
    }

    @GetMapping("/{batchId}")
    public PatientContract.BatchView status(@CurrentUser String username,
                                            @PathVariable String batchId) {
        return batchService.status(batchId, username);
    }

    @PostMapping("/{batchId}/retry")
    public PatientContract.BatchView retry(@CurrentUser String username,
                                           @PathVariable String batchId) {
        return batchService.retry(batchId, username);
    }
}
