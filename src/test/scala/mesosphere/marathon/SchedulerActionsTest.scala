package mesosphere.marathon

import akka.testkit.TestProbe
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.tracker.TaskTracker.TasksByApp
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ AppDefinition, AppRepository, GroupRepository, PathId }
import mesosphere.marathon.test.MarathonActorSupport
import mesosphere.mesos.protos
import mesosphere.mesos.protos.Implicits.{ slaveIDToProto, taskIDToProto }
import mesosphere.mesos.protos.SlaveID
import org.apache.mesos.Protos.{ TaskID, TaskState, TaskStatus }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.{ times, verify, verifyNoMoreInteractions, when }
import org.scalatest.Matchers
import org.scalatest.mock.MockitoSugar

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class SchedulerActionsTest extends MarathonActorSupport with MarathonSpec with Matchers with MockitoSugar {
  import system.dispatcher

  test("Reset rate limiter if application is stopped") {
    val queue = mock[LaunchQueue]
    val repo = mock[AppRepository]
    val taskTracker = mock[TaskTracker]

    val scheduler = new SchedulerActions(
      repo,
      mock[GroupRepository],
      mock[HealthCheckManager],
      taskTracker,
      queue,
      system.eventStream,
      TestProbe().ref,
      mock[MarathonConf]
    )

    val app = AppDefinition(id = PathId("/myapp"))

    when(repo.expunge(app.id)).thenReturn(Future.successful(Seq(true)))
    when(taskTracker.appTasksSync(app.id)).thenReturn(Set.empty[Protos.MarathonTask])

    val res = scheduler.stopApp(mock[SchedulerDriver], app)

    Await.ready(res, 1.second)

    verify(queue).purge(app.id)
    verify(queue).resetDelay(app)
    verifyNoMoreInteractions(queue)
  }

  test("Task reconciliation sends known running and staged tasks and empty list") {
    val queue = mock[LaunchQueue]
    val repo = mock[AppRepository]
    val taskTracker = mock[TaskTracker]
    val driver = mock[SchedulerDriver]

    val runningStatus = TaskStatus.newBuilder
      .setTaskId(TaskID.newBuilder.setValue("task_1"))
      .setState(TaskState.TASK_RUNNING)
      .build()

    val runningTask = MarathonTask.newBuilder
      .setId("task_1")
      .setStatus(runningStatus)
      .build()

    val stagedTask = MarathonTask.newBuilder
      .setId("task_2")
      .build()

    val stagedStatus = TaskStatus.newBuilder
      .setTaskId(TaskID.newBuilder.setValue(stagedTask.getId))
      .setState(TaskState.TASK_STAGING)
      .build()

    val stagedTaskWithSlaveId = MarathonTask.newBuilder
      .setId("task_3")
      .setSlaveId(SlaveID("slave 1"))
      .build()

    val stagedWithSlaveIdStatus = TaskStatus.newBuilder
      .setTaskId(TaskID.newBuilder.setValue(stagedTaskWithSlaveId.getId))
      .setSlaveId(stagedTaskWithSlaveId.getSlaveId)
      .setState(TaskState.TASK_STAGING)
      .build()

    val scheduler = new SchedulerActions(
      repo,
      mock[GroupRepository],
      mock[HealthCheckManager],
      taskTracker,
      queue,
      system.eventStream,
      TestProbe().ref,
      mock[MarathonConf]
    )

    val app = AppDefinition(id = PathId("/myapp"))

    when(taskTracker.appTasksSync(app.id)).thenReturn(Set(runningTask, stagedTask, stagedTaskWithSlaveId))
    when(repo.allPathIds()).thenReturn(Future.successful(Seq(app.id)))
    when(taskTracker.tasksByAppSync).thenReturn(TasksByApp.of(
      TaskTracker.AppTasks(app.id, Set(runningTask, stagedTask, stagedTaskWithSlaveId))
    ))

    Await.result(scheduler.reconcileTasks(driver), 5.seconds)

    verify(driver).reconcileTasks(Set(runningStatus, stagedStatus, stagedWithSlaveIdStatus).asJava)
    verify(driver).reconcileTasks(java.util.Arrays.asList())
  }

  test("Task reconciliation only one empty list, when no tasks are present in Marathon") {
    val queue = mock[LaunchQueue]
    val repo = mock[AppRepository]
    val taskTracker = mock[TaskTracker]
    val driver = mock[SchedulerDriver]

    val scheduler = new SchedulerActions(
      repo,
      mock[GroupRepository],
      mock[HealthCheckManager],
      taskTracker,
      queue,
      system.eventStream,
      TestProbe().ref,
      mock[MarathonConf]
    )

    val app = AppDefinition(id = PathId("/myapp"))

    when(taskTracker.appTasksSync(app.id)).thenReturn(Set.empty[MarathonTask])
    when(repo.allPathIds()).thenReturn(Future.successful(Seq()))
    when(taskTracker.tasksByAppSync).thenReturn(TasksByApp.empty)

    Await.result(scheduler.reconcileTasks(driver), 5.seconds)

    verify(driver, times(1)).reconcileTasks(java.util.Arrays.asList())
  }

  test("Kill orphaned task") {
    val queue = mock[LaunchQueue]
    val repo = mock[AppRepository]
    val taskTracker = mock[TaskTracker]
    val driver = mock[SchedulerDriver]

    val status = TaskStatus.newBuilder
      .setTaskId(TaskID.newBuilder.setValue("task_1"))
      .setState(TaskState.TASK_RUNNING)
      .build()

    val task = MarathonTask.newBuilder
      .setId("task_1")
      .setStatus(status)
      .build()

    val orphanedTask = MarathonTask.newBuilder
      .setId("orphaned task")
      .setStatus(status)
      .build()

    val scheduler = new SchedulerActions(
      repo,
      mock[GroupRepository],
      mock[HealthCheckManager],
      taskTracker,
      queue,
      system.eventStream,
      TestProbe().ref,
      mock[MarathonConf]
    )

    val app = AppDefinition(id = PathId("/myapp"))
    val orphanedApp = AppDefinition(id = PathId("/orphan"))

    when(taskTracker.appTasksSync(app.id)).thenReturn(Set(task))
    when(taskTracker.appTasksSync(orphanedApp.id)).thenReturn(Set(orphanedTask))
    when(repo.allPathIds()).thenReturn(Future.successful(Seq(app.id)))
    when(taskTracker.tasksByAppSync).thenReturn(TasksByApp.of(
      TaskTracker.AppTasks(app.id, Set(task)),
      TaskTracker.AppTasks(orphanedApp.id, Set(orphanedTask, task))
    ))

    Await.result(scheduler.reconcileTasks(driver), 5.seconds)

    verify(driver, times(1)).killTask(protos.TaskID(orphanedTask.getId))
  }
}
