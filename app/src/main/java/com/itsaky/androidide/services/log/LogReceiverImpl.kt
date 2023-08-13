/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.services.log

import android.util.Log
import com.itsaky.androidide.logsender.ILogReceiver
import com.itsaky.androidide.logsender.ILogSender
import com.itsaky.androidide.models.LogLine
import com.itsaky.androidide.tasks.executeAsyncProvideError
import com.itsaky.androidide.utils.ILogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Handles IPC connections from other proceses.
 *
 * @author Akash Yadav
 */
class LogReceiverImpl(consumer: ((LogLine) -> Unit)? = null) : ILogReceiver.Stub(), AutoCloseable {

  private val log = ILogger.newInstance("LogReceiverImpl")
  private val senderHandler = MultiLogSenderHandler()
  private val senders = LogSendersRegistry()
  private val consumerLock = ReentrantLock(true)
  private val shouldStartReaders = AtomicBoolean(false)

  internal var connectionObserver: ((ConnectionObserverParams) -> Unit)? = null

  internal var consumer: ((LogLine) -> Unit)? = consumer
    set(value) {
      field = value
      senderHandler.consumer = value?.let { synchronizeConsumer(value) }
    }

  private fun synchronizeConsumer(consumer: (LogLine) -> Unit): (LogLine) -> Unit {
    return { line -> consumerLock.withLock { consumer(line) } }
  }

  fun acceptSenders() {
    if (senderHandler.isAlive) {
      return
    }

    log.info("Starting log sender handler..")
    senderHandler.start()
  }

  override fun ping() {
    doAsync("ping") {
      Log.d("LogRecevier", "ping: Received a ping request")
    }
  }

  override fun connect(sender: ILogSender?) {
    doAsync("connect") {
      val port = senderHandler.getPort()
      if (port == -1) {
        log.error("A log sender is trying to connect, but log receiver is not started")
        return@doAsync
      }

      sender?.let { newSender ->

        val existingSender = senders.getByPackage(newSender.packageName)

        if (existingSender != null) {
          senderHandler.removeClient(existingSender.id)
        }

        if (existingSender?.isAlive() == true) {
          log.warn(
            "Client '${existingSender.packageName}' has been restarted with process ID '${newSender.pid}'" +
                " Previous connection with process ID '${existingSender.pid}' will be closed...")
          existingSender.onDisconnect()
        }

        connectSender(newSender, port)
      }
    }
  }

  private fun connectSender(sender: ILogSender, port: Int) {
    log.info("Connecting to client ${sender.packageName}")
    val caching = CachingLogSender(sender, port, false)
    this.senders.put(caching)

    if (shouldStartReaders.get()) {
      sender.startReader(port)
      caching.isStarted = true
    }

    logTotalConnected()

    connectionObserver?.invoke(ConnectionObserverParams(sender.id, this.senders.size))
  }

  internal fun startReaders() {
    this.shouldStartReaders.set(true)

    doAsync("startReaders") {
      senders.getPendingSenders().forEach { sender ->
        log.info("Notifying sender '${sender.packageName}' to start reading logs...")
        sender.startReader(sender.port)
      }
    }
  }

  override fun disconnect(packageName: String, senderId: String) {
    doAsync("disconnect") {
      val port = senderHandler.getPort()
      if (port == -1) {
        return@doAsync
      }

      if (!senders.containsKey(packageName)) {
        log.warn(
          "Received disconnect request from a log sender which is not connected: '${packageName}'")
        return@doAsync
      }

      disconnectSender(packageName, senderId)
    }
  }

  private fun disconnectSender(packageName: String, senderId: String) {
    log.info("Disconnecting from client: '${packageName}'")
    this.senderHandler.removeClient(senderId)
    this.senders.remove(packageName)
    logTotalConnected()

    connectionObserver?.invoke(ConnectionObserverParams(senderId, this.senders.size))
  }

  override fun close() {
    // TODO : Send close request to clients
    senderHandler.close()
    consumer = null
    connectionObserver = null
    senders.clear()
  }

  private fun doAsync(actionName: String, action: () -> Unit) {
    executeAsyncProvideError(action::invoke) { _, error ->
      if (error != null) {
        log.error("Failed to perform action '$actionName'", error)
      }
    }
  }

  private fun logTotalConnected() {
    log.info("Total clients connected: ${senders.size}")
  }
}