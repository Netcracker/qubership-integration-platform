def count = exchange.getProperty('reuseCount') + 1
exchange.setProperty('reuseCount', count)

if (exchange.getProperty('failAt2') == true && count == 2) {
    throw new Exception('Fail')
}
