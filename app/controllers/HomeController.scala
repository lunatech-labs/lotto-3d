package controllers

import javax.inject._
import play.api._


import play.api._
import libs.json.{JsValue, Reads, JsPath, Json}
import libs.ws.WS
import play.api.mvc._
import scala.concurrent.ExecutionContext.Implicits.global
import models.Lotto
import scala.concurrent.{Future, Promise}
/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HomeController @Inject() extends Controller {

  // Display our simple view
  def index = Action { implicit request =>
    Ok(views.html.index())
  }

  // Start our web socket streaming
  def lotto() = WebSocket.tryAccept[JsValue] { request  =>
    Lotto.join()
  }

  // Interrupt our actor
  def stop() = Action {
    Lotto.stop
    Ok("OK")
  }
}
