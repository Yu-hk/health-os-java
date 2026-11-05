package com.kangheng.healthos.controller.qa;

import java.util.List;

import jakarta.validation.Valid;

import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.contract.qa.QaContract;
import com.kangheng.healthos.service.qa.QaService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/qa")
public class QaController {

    private final QaService qaService;

    public QaController(QaService qaService) {
        this.qaService = qaService;
    }

    @PostMapping("/ask")
    public QaContract.AskResponse ask(@CurrentUser String username,
                                      @Valid @RequestBody QaContract.AskRequest request) {
        return qaService.ask(username, request);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<QaContract.MessageView> messages(@CurrentUser String username,
                                                 @PathVariable String conversationId) {
        return qaService.messages(conversationId, username);
    }

    /** 已注册的问答分支。加一路新分支后这里会自动多一项，不需要改接口。 */
    @GetMapping("/branches")
    public List<java.util.Map<String, Object>> branches(@CurrentUser String username) {
        return qaService.branches();
    }
}
