package com.lmax.disruptor.start;

/**
 * description:
 * create       2017/5/26 10:41
 *
 * @author email:baoyang@jd.com,ERP:baoyang3
 * @version 1.0.0
 */
public class LongEvent {
    private Long value;

    public void set(long value) {
        this.value = value;
    }

    public void clear() {
        value = null;
    }
}
