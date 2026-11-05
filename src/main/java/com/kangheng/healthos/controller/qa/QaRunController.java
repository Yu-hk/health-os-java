package com.kangheng.healthos.controller.qa;

import jakarta.validation.Valid;

import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.contract.qa.QaRunContract;
import com.kangheng.healthos.service.qa.run.QaRunService;
import com.kangheng.healthos.service.qa.run.QaRunStreamService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 问答运行的接口。
 *
 * <p>与 {@link QaController} 的分工：那个是一次性同步问答，这个是长任务的生命周期
 * （提交 → 观察 → 重试 → 取消）。两者产出的答案结构一致。
 *
 * <p>所有接口都按归属人隔离：越权一律 404，不返回 403。
 */
@RestController
@RequestMapping("/api/v1/qa/runs")
public class QaRunController {

    private final QaRunService runService;
    private final QaRunStreamService streamService;

    public QaRunController(QaRunService runService, QaRunStreamService streamService) {
        this.runService = runService;
        this.streamService = streamService;
    }

    /**
     * 提交一次问答。
     *
     * <p>返回 202：受理成功，模型调用在后台进行。客户端凭 {@code statusUrl}
     * 查询进度，或订阅事件流。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QaRunContract.AcceptedResponse submit(@CurrentUser String username,
                                                  @Valid @RequestBody QaRunContract.SubmitRequest request) {
        return runService.submit(username, request);
    }

    /** 运行详情：含节点明细与尝试记录。 */
    @GetMapping("/{runId}")
    public QaRunContract.RunDetail detail(@CurrentUser String username, @PathVariable String runId) {
        return runService.detail(username, runId);
    }

    /**
     * 增量拉事件。
     *
     * <p>{@code afterSeq} 之后的事件，按 seq 升序，最多 500 条。
     * SSE 断了就用这个接口补齐。
     */
    @GetMapping("/{runId}/events")
    public QaRunContract.EventPage events(@CurrentUser String username,
                                          @PathVariable String runId,
                                          @RequestParam(defaultValue = "0") int afterSeq,
                                          @RequestParam(defaultValue = "100") int limit) {
        return runService.events(username, runId, afterSeq, limit);
    }

    /**
     * 订阅事件流（SSE）。
     *
     * <p>断点续传：客户端记住最后收到的 {@code id}（就是 seq），重连时带上
     * {@code afterSeq}，服务端只补之后的事件。
     */
    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@CurrentUser String username,
                             @PathVariable String runId,
                             @RequestParam(defaultValue = "0") int afterSeq) {
        return streamService.subscribe(username, runId, afterSeq);
    }

    /**
     * 重试失败节点。
     *
     * <p>{@code nodeKeys} 留空表示重跑全部失败节点。已成功的节点不会被重跑——
     * 它们的依据已经冻结在运行里，重跑既浪费也可能产出不一致的答案。
     */
    @PostMapping("/{runId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QaRunContract.AcceptedResponse retry(@CurrentUser String username,
                                                 @PathVariable String runId,
                                                 @RequestBody(required = false)
                                                 QaRunContract.RetryRequest request) {
        return runService.retry(username, runId,
                request == null ? new QaRunContract.RetryRequest(null, null, null) : request);
    }

    /** 取消运行。已终态的运行返回当前状态（幂等）。 */
    @PostMapping("/{runId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QaRunContract.AcceptedResponse cancel(@CurrentUser String username,
                                                 @PathVariable String runId,
                                                 @RequestBody(required = false)
                                                 QaRunContract.CancelRequest request) {
        return runService.cancel(username, runId, request == null ? null : request.expectedVersion());
    }
}
