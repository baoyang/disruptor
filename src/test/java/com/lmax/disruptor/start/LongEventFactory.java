package com.lmax.disruptor.start;

import com.lmax.disruptor.EventFactory;

/**
 * description:
 * create       2017/5/26 10:41
 *
 * @author email:baoyang@jd.com,ERP:baoyang3
 * @version 1.0.0
 */
public class LongEventFactory implements EventFactory<LongEvent>
{
    public LongEvent newInstance()
    {
        return new LongEvent();
    }
}
