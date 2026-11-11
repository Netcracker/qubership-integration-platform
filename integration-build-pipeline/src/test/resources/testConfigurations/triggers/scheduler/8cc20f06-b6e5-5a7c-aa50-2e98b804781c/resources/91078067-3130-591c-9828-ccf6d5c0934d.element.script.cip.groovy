exchange.setProperty('snapshotSchedulerFallbackReached', true)
exchange.getMessage().setBody('Fallback')
