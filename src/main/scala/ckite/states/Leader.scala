package ckite.states

import java.util.concurrent.Executors
import java.util.concurrent.Future
import scala.util.Try
import ckite.Cluster
import ckite.rpc.WriteCommand
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import scala.collection.JavaConversions._
import ckite.rpc.RequestVoteResponse
import ckite.util.Logging
import ckite.rpc.AppendEntriesResponse
import ckite.rpc.RequestVote
import ckite.rpc.AppendEntries
import ckite.rpc.LogEntry
import ckite.RLog
import java.lang.Boolean
import ckite.Member
import ckite.rpc.EnterJointConsensus
import ckite.rpc.MajorityJointConsensus
import ckite.rpc.LeaveJointConsensus
import ckite.exception.NoMajorityReachedException
import ckite.util.CKiteConversions._
import ckite.rpc.ReadCommand
import ckite.rpc.Command
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import com.twitter.concurrent.NamedPoolThreadFactory
import ckite.RemoteMember
import java.util.concurrent.ScheduledThreadPoolExecutor
import scala.collection.mutable.ArrayBuffer
import ckite.rpc.NoOp
import ckite.rpc.NoOp
import scala.collection.JavaConverters._
import java.util.concurrent.TimeoutException
import scala.concurrent._
import scala.concurrent.duration._

/**
 * 	•! Initialize nextIndex for each to last log index + 1
 * •! Send initial empty AppendEntries RPCs (heartbeat) to each
 * follower; repeat during idle periods to prevent election
 * timeouts (§5.2)
 * •! Accept commands from clients, append new entries to local
 * log (§5.3)
 * •! Whenever last log index ! nextIndex for a follower, send
 * AppendEntries RPC with log entries starting at nextIndex,
 * update nextIndex if successful (§5.3)
 * •! If AppendEntries fails because of log inconsistency,
 * decrement nextIndex and retry (§5.3)
 * •! Mark entries committed if stored on a majority of servers
 * and some entry from current term is stored on a majority of
 * servers. Apply newly committed entries to state machine.
 * •! Step down if currentTerm changes (§5.5)
 */
class Leader(cluster: Cluster) extends State {

  val ReplicationTimeout = cluster.configuration.appendEntriesTimeout
  val startTime = System.currentTimeMillis()
  
  val heartbeater = new Heartbeater(cluster)
  val followersInfo = new ConcurrentHashMap[String, Long]()

  override def begin(term: Int) = {
    if (term < cluster.local.term) {
      LOG.debug(s"Cant be a Leader of term $term. Current term is ${cluster.local.term}")
      cluster.local.becomeFollower(cluster.local.term)
    } else {
      cluster.updateLeader(cluster.local.id)
      resetLastLog
      resetNextIndexes
      resetFollowerInfo
      heartbeater start term
      LOG.debug("Append a NoOp as part of Leader initialization")
      on[Unit](NoOp())
      LOG.info(s"Start being Leader")
    }
  }
  
  private def noOp(): LogEntry = LogEntry(cluster.local.term, cluster.rlog.nextLogIndex, NoOp())

  private def resetLastLog = cluster.rlog.resetLastLog()

  private def resetNextIndexes = {
    val nextIndex = cluster.rlog.lastLog.intValue() + 1
    cluster.membership.remoteMembers.foreach { member => member.setNextLogIndex(nextIndex) }
  }

  override def stop = {
    LOG.debug("Stop being Leader")
    heartbeater stop
    
    cluster.setNoLeader
  }

  override def on[T](command: Command): T = {
    command match {
      case w: WriteCommand => onWriteCommand[T](w)
      case r: ReadCommand => onReadCommand[T](r)
    }

  }

  private def onWriteCommand[T](command: WriteCommand): T = {
    val logEntry = LogEntry(cluster.local.term, cluster.rlog.nextLogIndex, command)
    LOG.debug(s"Will wait for a majority consisting of ${cluster.membership.majoritiesMap} until $ReplicationTimeout ms")
    val promise = cluster.rlog.append(logEntry).asInstanceOf[Promise[T]]
    replicate(logEntry)
    try {
      Await.result(promise.future, cluster.configuration.appendEntriesTimeout millis)
    } catch {
      case e: TimeoutException => throw new NoMajorityReachedException(logEntry)
    }
  }
  
  private def replicate(logEntry: LogEntry) = {
    if (cluster.hasRemoteMembers) {
      cluster.broadcastAppendEntries(logEntry.term)
    } else {
      LOG.debug(s"No member to replicate")
      cluster.rlog commit logEntry.index
    }
  }
  
  
  private def onReadCommand[T](command: ReadCommand): T = {
    (cluster.rlog execute command).asInstanceOf[T]
  }

  override def on(appendEntries: AppendEntries): AppendEntriesResponse = {
    if (appendEntries.term < cluster.local.term) {
      AppendEntriesResponse(cluster.local.term, false)
    } else {
      stepDown(Some(appendEntries.leaderId), appendEntries.term)
      cluster.local on appendEntries
    }
  }

  override def on(requestVote: RequestVote): RequestVoteResponse = {
    if (requestVote.term <= cluster.local.term) {
      RequestVoteResponse(cluster.local.term, false)
    } else {
      stepDown(None, requestVote.term)
      cluster.local on requestVote
    }
  }

  override def on(jointConsensusCommited: MajorityJointConsensus) = {
    LOG.debug(s"Sending LeaveJointConsensus")
    cluster.on[Boolean](LeaveJointConsensus(jointConsensusCommited.newBindings))
  }
  
  override protected def getCluster: Cluster = cluster

  override def toString = "Leader"

  override def info(): StateInfo = {
    val now = System.currentTimeMillis()
    val followers = followersInfo.map {
      tuple =>
        val member = cluster.obtainRemoteMember(tuple._1).get
        (tuple._1, FollowerInfo(lastAck(tuple._2, now), member.matchIndex.intValue(), member.nextLogIndex.intValue()))
    }
    LeaderInfo(leaderUptime.toString, followers.toMap)
  }
  
  private def leaderUptime = System.currentTimeMillis() - startTime millis
  
  private def lastAck(ackTime: Long, now: Long) = if (ackTime > 0) (now - ackTime millis).toString else "Never"
  
  private def resetFollowerInfo = {
    cluster.membership.remoteMembers.foreach { member => followersInfo.put(member.id, -1) }
  }
  
  override def onAppendEntriesResponse(member: RemoteMember, request: AppendEntries, response: AppendEntriesResponse) = {
      val time = System.currentTimeMillis() 
	  if (!request.entries.isEmpty) {
            onAppendEntriesResponseUpdateNextLogIndex(member, request, response)
       }
      val nextIndex = member.nextLogIndex.intValue()
       if (isLogEntryInSnapshot(nextIndex)) {
          val wasEnabled = member.disableReplications()
          if (wasEnabled) { 
        	LOG.debug(s"Next LogIndex #$nextIndex to be sent to ${member} is contained in a Snapshot. An InstallSnapshot will be sent.")
            sendSnapshot(member)
          }
      }
      followersInfo.put(member.id, time)
  }

  private def onAppendEntriesResponseUpdateNextLogIndex(member: RemoteMember, appendEntries: AppendEntries, appendEntriesResponse: AppendEntriesResponse) = {
    val lastEntrySent = appendEntries.entries.last.index
    if (appendEntriesResponse.success) {
      member.ackLogEntry(lastEntrySent)
      LOG.debug(s"Member ${member} ack - LogIndex sent #$lastEntrySent - next LogIndex is #${member.nextLogIndex}")
      tryToCommitEntries(lastEntrySent)
    } else {
      member.decrementNextLogIndex()
      LOG.debug(s"Member ${member} reject - LogIndex sent #$lastEntrySent - next LogIndex is #${member.nextLogIndex}")
    }
  }
  
  private def tryToCommitEntries(lastEntrySent: Int) = {
     val currentCommitIndex = cluster.rlog.commitIndex.intValue()
      (currentCommitIndex + 1) to lastEntrySent foreach { index =>
        val members = cluster.membership.remoteMembers.filter { remoteMember => remoteMember.matchIndex.intValue() >= index }
        if (cluster.membership.reachMajority(members :+ cluster.local)) {
          cluster.rlog commit index
        }
    }
  }
  
  private def isLogEntryInSnapshot(logIndex: Int): Boolean = {
    val some = cluster.rlog.getSnapshot().map {snapshot => logIndex <= snapshot.lastLogEntryIndex }
    some.getOrElse(false).asInstanceOf[Boolean]
  }

  def sendSnapshot(member: RemoteMember) = {
    val snapshot = cluster.rlog.getSnapshot().get
    LOG.debug(s"Sending InstallSnapshot to ${member} containing $snapshot")
    val future = member.sendSnapshot(snapshot)
    future.map { success =>
      cluster.inContext {
        if (success){
        	LOG.debug("Succesful InstallSnapshot")
        	member.ackLogEntry(snapshot.lastLogEntryIndex)
        	tryToCommitEntries(snapshot.lastLogEntryIndex)
        } else {
          LOG.debug("Failed InstallSnapshot")
        }
        member.enableReplications()
      }
    }
  }
}


class Heartbeater(cluster: Cluster) extends Logging {

  val scheduledHeartbeatsPool = new ScheduledThreadPoolExecutor(1, new NamedPoolThreadFactory("Heartbeater", true))
  
  def start(term: Int) = {
    LOG.debug("Start Heartbeater")

    val task:Runnable = () => {
      cluster updateContextInfo

      LOG.trace("Heartbeater running")
      cluster.broadcastAppendEntries(term)
    } 
    scheduledHeartbeatsPool.scheduleAtFixedRate(task, 0, cluster.configuration.heartbeatsInterval, TimeUnit.MILLISECONDS)

  }

  def stop() = {
    LOG.debug("Stop Heartbeater")
    scheduledHeartbeatsPool.shutdownNow()
  }
}