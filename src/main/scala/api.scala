package org.asyncmongo.api

import akka.dispatch.Future
import akka.util.Timeout
import akka.util.duration._

import org.asyncmongo.actors.MongoConnection
import org.asyncmongo.bson._
import org.asyncmongo.handlers._
import org.asyncmongo.protocol._
import org.asyncmongo.protocol.messages._

case class DB(dbName: String, connection: MongoConnection, implicit val timeout :Timeout = Timeout(5 seconds)) {
  def find[T, U, V](collection: String, query: T, fields: Option[U], skip: Int, limit: Int)(implicit writer: BSONWriter[T], writer2: BSONWriter[U], handler: BSONReaderHandler, reader: BSONReader[V], m: Manifest[V]) :Future[Cursor[V]] = {
    val op = Query(0, collection, skip, 19)
    val bson = writer.write(query)
    if(fields.isDefined)
      bson.writeBytes(writer2.write(fields.get))
    val message = WritableMessage(op, bson)
    
    connection.ask(message).map { response =>
      new Cursor(response, connection, op)
    }
  }
  
  def count(collection: String) :Future[Int] = {
    import DefaultBSONHandlers._
    connection.ask(Count(dbName, collection).makeWritableMessage).map { response =>
      DefaultBSONReaderHandler.handle(response.reply, response.documents).next.find(_.name == "n").get match {
        case BSONDouble(_, n) => n.toInt
        case _ => throw new RuntimeException("...")
      }
    }
  }
  
  def insert[T](collection: String, document: T)(implicit writer: BSONWriter[T]) :Unit = {
    val op = Insert(0, collection)
    val bson = writer.write(document)
    val message = WritableMessage(op, bson)
    connection.send(message)
  }
  
  def insert[T, U](collection: String, document: T, writeConcern: GetLastError)(implicit writer: BSONWriter[T], handler: BSONReaderHandler, reader: BSONReader[U], m: Manifest[U]) :Future[U] = {
    val op = Insert(0, collection)
    val bson = writer.write(document)
    val message = WritableMessage(op, bson)
    connection.ask(message, writeConcern).map { response =>
      handler.handle(response.reply, response.documents).next
    }
  }
  
  def remove[T](collection: String, query: T)(implicit writer: BSONWriter[T]) :Unit = remove(collection, query, false)
  
  def remove[T](collection: String, query: T, firstMatchOnly: Boolean)(implicit writer: BSONWriter[T]) : Unit = {
    val op = Delete(collection, if(firstMatchOnly) 1 else 0)
    val bson = writer.write(query)
    val message = WritableMessage(op, bson)
    connection.send(message)
  }
  
  def remove[T, U](collection: String, query: T, writeConcern: GetLastError, firstMatchOnly: Boolean = false)(implicit writer: BSONWriter[T], handler: BSONReaderHandler, reader: BSONReader[U], m: Manifest[U]) :Future[U] = {
    val op = Delete(collection, if(firstMatchOnly) 1 else 0)
    val bson = writer.write(query)
    val message = WritableMessage(op, bson)
    connection.ask(message, writeConcern).map { response =>
      handler.handle(response.reply, response.documents).next
    }
  }
}

class Cursor[T](response: ReadReply, connection: MongoConnection, query: Query)(implicit handler: BSONReaderHandler, reader: BSONReader[T], timeout :Timeout) {
  lazy val iterator :Iterator[T] = handler.handle(response.reply, response.documents)
  def next :Option[Future[Cursor[T]]] = {
    if(hasNext) {
      println("cursor: call next")
      val op = GetMore(query.fullCollectionName, query.numberToReturn, response.reply.cursorID)
      Some(connection.ask(WritableMessage(op)).map { response => new Cursor(response, connection, query) })
    } else None
  }
  def hasNext :Boolean = response.reply.cursorID != 0
  def close = if(hasNext) {
    connection.send(WritableMessage(KillCursors(Set(response.reply.cursorID))))
  }
}

object Cursor {
  // for test purposes
  import akka.dispatch.Await
  def stream[T](cursor: Cursor[T])(implicit timeout :Timeout) :Stream[T] = {
    implicit val ec = MongoConnection.system.dispatcher
    if(cursor.iterator.hasNext) {
      Stream.cons(cursor.iterator.next, stream(cursor))
    } else if(cursor.hasNext) {
      stream(Await.result(cursor.next.get, timeout.duration))
    } else Stream.empty
  }

  import play.api.libs.iteratee._
  import play.api.libs.concurrent.{Promise => PlayPromise, _}


  def enumerate[T](futureCursor: Option[Future[Cursor[T]]]) :Enumerator[T] = {
    var currentCursor :Option[Cursor[T]] = None
    Enumerator.fromCallback { () =>
      if(currentCursor.isDefined && currentCursor.get.iterator.hasNext){
        println("enumerate: give next element from iterator")
        PlayPromise.pure(Some(currentCursor.get.iterator.next))
      } else if(currentCursor.isDefined && currentCursor.get.hasNext) {
        println("enumerate: fetching next cursor")
        new AkkaPromise(currentCursor.get.next.get.map { cursor =>
          println("redeemed from next cursor")
          currentCursor = Some(cursor)
          Some(cursor.iterator.next)
        })
      } else if(!currentCursor.isDefined && futureCursor.isDefined) {
        println("enumerate: fetching from first future")
        new AkkaPromise(futureCursor.get.map { cursor =>
          println("redeemed from first cursor")
          currentCursor = Some(cursor)
          Some(cursor.iterator.next)
        })
      } else PlayPromise.pure(None)
    }
  }
}

object Test {
  import akka.dispatch.Await
  import akka.pattern.ask
  import DefaultBSONHandlers._
  import play.api.libs.iteratee._
   
  implicit val timeout = Timeout(5 seconds)

  def test = {
    val mongo = DB("plugin", MongoConnection(List("localhost" -> 27017)))
    val query = new Bson()//new HashMap[Object, Object]()
    query.writeElement("name", "Jack")
    val future = mongo.find("plugin.acoll", query, None, 2, 0)
    mongo.count("acoll").onComplete {
      case Left(t) =>
      case Right(t) => println("count on plugin.acoll gave " + t)
    }
    println("Test: future is " + future)
    val toSave = new Bson()
    val tags = new Bson()
    tags.writeElement("tag1", "yop")
    tags.writeElement("tag2", "...")
    toSave.writeElement("name", "Kurt")
    toSave.writeElement("tags", tags)
    //Cursor.stream(Await.result(future, timeout.duration)).print("\n")
    /*Cursor.enumerate(Some(future))(Iteratee.foreach { t =>
      println("fetched t=" + DefaultBSONIterator.pretty(t))
    })
    mongo.insert("plugin.acoll", toSave, GetLastError()).onComplete {
      case Left(t) => println("error!, throwable = " + t)
      case Right(t) => println("inserted, GetLastError=" + DefaultBSONIterator.pretty(t))
    }*/

  }
}