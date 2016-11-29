package models

import akka.util.Timeout
import play.api.libs.iteratee.{Enumerator, Iteratee}
import play.api.libs.json._

import play.api.libs.iteratee._
import play.api.libs.concurrent._
import akka.util.Timeout
import play.api.Play.current
import concurrent.{Await, Future, ExecutionContext}
import ExecutionContext.Implicits.global

import akka.pattern.ask
import play.api.mvc._
import akka.actor._
import scala.concurrent.duration._
import play.api.libs.ws.WS
import play.api.{Play, Logger}
import scala.util.Random
import play.api.libs.json.JsString

object Meetup {

  // This is our actor system that we want to shutdown
  @volatile var cancel:Cancellable = null

  def apply(f: Seq[JsValue] => Unit) {

    // Get our keys
    val apiKey = Play.configuration.getString("meetup.api.key").getOrElse("yourkey")
    val meetupId = Play.configuration.getString("meetup.id").getOrElse("meetup")
    val requestUrl = s"https://api.meetup.com/2/rsvps?key=${apiKey}&sign=true&rsvp=yes&event_id=${meetupId}&page=100"
    // Our WS lib (should now be injected but I don't like it)
    val response = WS.url(requestUrl).get()

    response.map { json =>
        Logger.info(json.json.toString)
          (json.json \ "results").as[Seq[JsObject]].map { a =>

            val name = (a \ "member" \ "name").as[String]
            val picture = (a \ "member_photo" \ "photo_link").asOpt[String].getOrElse("https://upload.wikimedia.org/wikipedia/commons/3/37/No_person.jpg")
            Json.obj(
              "name" -> name,
              "photo" -> picture
            )
          }
      }.map { members =>
          implicit val timeout = Timeout(1 second)
          Logger.info("~" + members.toString())
          // we start our actor. It is going to execute our list of members
          cancel = Akka.system.scheduler.schedule(
            0 seconds,
            2 seconds
          )(f(members))
        }
  }


  def stop = {
    // This is a bit brutal
    cancel.cancel()
  }
}


object Lotto {

  implicit val timeout = Timeout(1 second)

  lazy val default = {
    val lottoActor = Akka.system.actorOf(Props[Lotto])
    Meetup(members => lottoActor ! Send(members))
    lottoActor
  }


  // Ask our Scala experts!
  def join(): scala.concurrent.Future[Either[Result, (Iteratee[JsValue, _], Enumerator[JsValue])]] = {

    // instanciate our actor
    (default ? Ok).map {
      case Connected(enumerator) =>
         // Just ignore the input
        val iteratee = Iteratee.ignore[JsValue]
        Right((iteratee, enumerator))

      case Stop() =>
        val iteratee = Done[JsValue, Unit]((),Input.EOF)
        val enumerator =  Enumerator[JsValue]().andThen(Enumerator.enumInput(Input.EOF))

        Right((iteratee,enumerator))

    }
  }

  def stop = {
    // Wait 30 sec and stop the sending of information
    Akka.system.scheduler.scheduleOnce(Random.nextInt(30) seconds, default, Stop)
 }

}


class Lotto extends Actor {

  // Send to all our clients
  val (lottoEnumerator, channel) = Concurrent.broadcast[JsValue]

  def receive = {

    case Ok => {
      sender ! Connected(lottoEnumerator)
    }

    // We interrupt our actor
    case Stop => {
      Meetup.stop
      channel.eofAndEnd()
    }

    case Send(members) => {
      // We are cheating here
      val member = members(Random.nextInt(99))
      val picture = (member \ "photo" ).as[String]
      Logger.info("sending " + (member \ "name").as[String] + " " + picture)

      // Push it down to the client
      channel.push(member)
    }
  }

}

case class Ok()
case class Stop()
case class Send(members: Seq[JsValue])
case class Connected(enumerator:Enumerator[JsValue])
