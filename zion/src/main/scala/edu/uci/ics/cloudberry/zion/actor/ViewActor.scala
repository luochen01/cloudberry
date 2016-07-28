package edu.uci.ics.cloudberry.zion.actor

import akka.actor.{Actor, ActorLogging, ActorRef, Stash}
import edu.uci.ics.cloudberry.zion.model._
import org.joda.time.{DateTime, Interval}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import akka.pattern.ask
import akka.util.Timeout
import edu.uci.ics.cloudberry.zion.common.Config

abstract class ViewActor(val sourceActor: ActorRef, fViewStore: Future[ViewMetaRecord], config: Config)(implicit ec: ExecutionContext)
  extends Actor with Stash with ActorLogging {

  import ViewActor._

  def queryTemplate: DBQuery

  def updateInterval(): FiniteDuration

  var key: String = _
  var sourceName: String = _
  var summaryLevel: SummaryLevel = _
  var startTime: DateTime = _
  var updateCycle: Duration = _
  var lastVisitTime: DateTime = _
  var lastUpdateTime: DateTime = _
  var visitTimes: Int = 0

  implicit val sourceAskTimeOut: Timeout = Timeout(config.ViewTimeOut)

  protected def updateMetaRecord(): Unit = context.parent !
    ViewMetaRecord(sourceName, key, summaryLevel, startTime, lastVisitTime, lastUpdateTime, visitTimes, updateCycle)

  def mergeResult(viewResponse: Response, sourceResponse: Response): Response

  def askViewOnly(query: DBQuery): Future[Response]

  def createSourceQuery(initQuery: DBQuery, unCovered: Seq[Interval]): DBQuery

  def updateView(from: DateTime, to: DateTime): Future[Unit]

  def query(query: DBQuery): Future[Response] = {
    val requiredTime = query.predicates.find(_.isInstanceOf[TimePredicate]).map(_.asInstanceOf[TimePredicate].intervals).get
    val unCovered = getTimeRangeDifference(new Interval(startTime, lastUpdateTime), requiredTime)
    val fViewResponse = askViewOnly(query)
    if (unCovered.isEmpty) {
      return fViewResponse
    } else {
      val sourceQuery = createSourceQuery(query, unCovered)
      val fSourceResponse = sourceActor ? sourceQuery
      for {
        viewResponse <- fViewResponse
        sourceResponse <- fSourceResponse
      } yield mergeResult(viewResponse, sourceResponse.asInstanceOf[Response])
    }
  }

  override def preStart(): Unit = {
    fViewStore.onComplete {
      case Success(store) => {
        key = store.viewKey
        sourceName = store.sourceName
        summaryLevel = store.summaryLevel
        startTime = store.startTime
        updateCycle = store.updateCycle
        lastVisitTime = store.lastVisitTime
        lastUpdateTime = store.lastUpdateTime
        visitTimes = store.visitTimes
        self ! DoneInitializing
      }
      case Failure(t) => "error happens: " + t.getMessage
    }
  }

  override def receive: Receive = initializing

  private def initializing: Receive = {
    case DoneInitializing =>
      unstashAll()
      context.become(routine)
    case msg => stash()
  }


  private def routine: Receive = {
    case query: DBQuery => {
      log.info(self + " get query " + query + " from : " + sender())
      val thisSender = sender()
      if (summaryLevel.isFinerThan(query.summaryLevel)) {
        this.query(query).onComplete {
          case Success(response) => thisSender ! response
          case Failure(exception) => log.error(exception, exception.getMessage)
        }
        lastVisitTime = new DateTime()
        visitTimes += 1
      } else {
        sourceActor.forward(query)
      }
    }
    case UpdateViewMsg => {
      val now = DateTime.now
      updateView(lastUpdateTime, now) onComplete {
        case Success(x) =>
          lastUpdateTime = now
          updateMetaRecord()
        case Failure(throwable) => log.error(throwable, throwable.getMessage)
      }
    }
  }

  context.system.scheduler.schedule(updateInterval, updateInterval, self, UpdateViewMsg)
}

object ViewActor {

  object DoneInitializing

  object UpdateViewMsg

  def getTimeRangeDifference(actual: Interval, expected: Seq[Interval]): Seq[Interval] = {
    if (expected.forall(actual.contains)) {
      Seq.empty[Interval]
    } else {
      // may need update query, but should not need to create query.
      import scala.collection.mutable.ArrayBuffer
      val futureInterval = new Interval(Math.min(actual.getStartMillis, expected.map(_.getStartMillis).min),
                                        Math.max(actual.getEndMillis, expected.map(_.getEndMillis).max))
      val intervals = ArrayBuffer.empty[Interval]
      if (futureInterval.getStartMillis < actual.getStartMillis) {
        intervals += new Interval(futureInterval.getStartMillis, actual.getStartMillis)
      }
      if (actual.getEndMillis < futureInterval.getEndMillis) {
        intervals += new Interval(actual.getEndMillis, futureInterval.getEndMillis)
      }
      intervals
    }
  }

}