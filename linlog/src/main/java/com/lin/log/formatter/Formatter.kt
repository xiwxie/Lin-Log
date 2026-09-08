package com.lin.log.formatter

public fun interface Formatter<T> {
    public fun format(data: T): String
}
