package cn.gbk.logbacklens.spike

internal object InteractionFixture {
    const val SOURCE = """class DemoService {
    interface Logger {
        void info(String template, Object... arguments);
    }

    private Logger log;

    void submit(String orderId, long accountId) {
        log.info("order {} {}", orderId, accountId);
    }
}
"""

    const val MULTI_TARGET_SOURCE = """class DemoService {
    interface Logger {
        void info(String template, Object... arguments);
    }

    private Logger log;

    void submit(String customerId, long accountId) {
        log.info("order {} {}", customerId, accountId);
        log.info("audit {} {}", customerId, accountId);
        log.info("orde " + customerId + "r   {} {}", customerId, accountId);
        log.info("orde {} "
                + customerId + "r   {} {}", customerId, customerId, accountId);
    }
}
"""
}
