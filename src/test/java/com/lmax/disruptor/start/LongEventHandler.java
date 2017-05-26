package com.lmax.disruptor.start;

import com.lmax.disruptor.EventHandler;

/**
 * description:
 * create       2017/5/26 10:42
 *
 * @author email:baoyang@jd.com,ERP:baoyang3
 * @version 1.0.0
 */
public class LongEventHandler implements EventHandler<LongEvent> {
    public void onEvent(LongEvent event, long sequence, boolean endOfBatch) {
        System.out.println("Event: " + event + " ,Sequence: " + sequence + " ,EndOfBatch: " + endOfBatch);
    }
}
