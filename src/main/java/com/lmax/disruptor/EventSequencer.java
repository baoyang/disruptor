package com.lmax.disruptor;

public interface EventSequencer<T> extends DataProvider<T>, Sequenced
{       //只是为了将Sequencer和DataProvider合起来。

}
