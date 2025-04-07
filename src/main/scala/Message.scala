// Определение сообщений
trait Message

// Команды - что актор может выполнять
trait Commands extends Message

// События - что актор сообщает о результатах
trait Events extends Message