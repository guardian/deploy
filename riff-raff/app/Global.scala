import deployment.MessageBus
import notification.IrcClient
import play.{Application, GlobalSettings}

class Global extends GlobalSettings {
  override def onStart(app: Application) {
    // initialise IRC actor
    IrcClient.init()
    MessageBus.init()
  }

  override def onStop(app: Application) {
    IrcClient.shutdown()
  }
}
