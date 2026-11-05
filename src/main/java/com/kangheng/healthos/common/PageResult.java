package com.kangheng.healthos.common;

import java.util.List;
import java.util.function.Function;

import com.baomidou.mybatisplus.core.metadata.IPage;

/**
 * 分页返回体。页码从 1 开始，单页上限 200，防止被当成全量导出接口使用。
 */
public record PageResult<T>(List<T> items, long total, long page, long size) {

    public static final int MAX_SIZE = 200;

    public static <E, T> PageResult<T> of(IPage<E> source, Function<E, T> mapper) {
        List<T> items = source.getRecords().stream().map(mapper).toList();
        return new PageResult<>(items, source.getTotal(), source.getCurrent(), source.getSize());
    }

    public static int clampSize(int size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, MAX_SIZE);
    }

    public static int clampPage(int page) {
        return Math.max(page, 1);
    }
}
