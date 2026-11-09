exchange.setProperty('snapshotSchedulerScriptReached', true)
if (Boolean.TRUE == exchange.getProperty('snapshotSchedulerFail', Boolean)) {
    throw new IllegalStateException('Scheduled script failure')
}
exchange.getMessage().setBody('Triggered')
