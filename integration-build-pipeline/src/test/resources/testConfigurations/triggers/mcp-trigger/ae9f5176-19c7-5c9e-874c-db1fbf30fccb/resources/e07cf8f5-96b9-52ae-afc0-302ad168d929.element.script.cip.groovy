exchange.setProperty('snapshotMcpFallbackReached', true)
exchange.getMessage().setBody('Fallback')
