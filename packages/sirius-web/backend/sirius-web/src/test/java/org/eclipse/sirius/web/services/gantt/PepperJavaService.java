/*******************************************************************************
 * Copyright (c) 2026 Obeo.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Obeo - initial API and implementation
 *******************************************************************************/

package org.eclipse.sirius.web.services.gantt;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.sirius.components.core.api.IFeedbackMessageService;
import org.eclipse.sirius.components.interpreter.SimpleCrossReferenceProvider;
import org.eclipse.sirius.components.representations.Message;
import org.eclipse.sirius.components.representations.MessageLevel;

import pepper.peppermm.AbstractTask;
import pepper.peppermm.DependencyLink;
import pepper.peppermm.PepperFactory;
import pepper.peppermm.StartOrEnd;
import pepper.peppermm.Task;
import pepper.peppermm.Workpackage;

/**
 * Java Service for the task related views, for tests.
 *
 * @author ncouvert
 */
public class PepperJavaService {

    private static final String NEW_TASK = "New Task";

    private final SimpleCrossReferenceProvider simpleCrossReferenceProvider = new SimpleCrossReferenceProvider();

    private final IFeedbackMessageService feedbackMessageService;

    public PepperJavaService(IFeedbackMessageService feedbackMessageService) {
        this.feedbackMessageService = Objects.requireNonNull(feedbackMessageService);
    }

    public void editTask(EObject eObject, String name, String description, Instant startTime, Instant endTime, Integer progress) {
        if (eObject instanceof Task task) {
            if (name != null) {
                task.setName(name);
            }
            if (description != null) {
                task.setDescription(description);
            }
            if (endTime != null && startTime != null) {
                Instant newStartTime = startTime;
                Instant newEndTime = endTime;
                //set the instants to xx:00 for the start time and xx:59 for the end time
                if ((newEndTime.atZone(ZoneId.systemDefault()).getHour() == 0 || newEndTime.atZone(ZoneId.systemDefault()).getHour() == 12) && !startTime.equals(endTime)) {
                    newEndTime = newEndTime.minus(1, ChronoUnit.MINUTES);
                }
                if (newStartTime.atZone(ZoneId.systemDefault()).getMinute() == 1) {
                    newStartTime = startTime.minus(1, ChronoUnit.MINUTES);
                }

                long differenceEnd = task.getEndTime().getEpochSecond() - newEndTime.getEpochSecond();
                long differenceStart = task.getStartTime().getEpochSecond() - newStartTime.getEpochSecond();
                boolean endPointed = false;
                boolean startPointed = false;
                List<DependencyLink> dependencies = task.getDependencies();
                for (DependencyLink dep : dependencies) {
                    if (dep.getTargetKind() == StartOrEnd.END) {
                        endPointed = true;
                    } else {
                        startPointed = true;
                    }
                }
                if (dependencies.isEmpty() || differenceEnd != differenceStart) {
                    if (startPointed && !endPointed) {
                        newEndTime = newEndTime.plus(differenceStart, ChronoUnit.SECONDS);
                        this.taskSetDuration(task, newStartTime, newEndTime);
                        task.setEndTime(newEndTime);
                    } else if (!startPointed && endPointed) {
                        newStartTime = newStartTime.plus(differenceEnd, ChronoUnit.SECONDS);
                        this.taskSetDuration(task, newStartTime, newEndTime);
                        task.setStartTime(newStartTime);
                    } else if (!startPointed && !endPointed) {
                        this.taskSetDuration(task, newStartTime, newEndTime);
                        task.setStartTime(newStartTime);
                        task.setEndTime(newEndTime);
                    }

                    if (!startPointed || !endPointed) {
                        followTaskMoveDependency(task);
                    }
                }
            }
            if (progress != null) {
                task.setProgress(progress);
            }
        }
    }

    private void taskSetDuration(Task task, Instant start, Instant end) {
        int duration = (int) ChronoUnit.HOURS.between(start, end) + 1; //+1 because between(00:00, 00:59) = 0. We want 1.
        task.setDuration(duration);
    }

    public void createTask(EObject context) {
        Task task = PepperFactory.eINSTANCE.createTask();
        task.setName(NEW_TASK);
        if (context instanceof AbstractTask abstractTask) {
            // The new task follows the context task and has the same duration as the context task.
            if (abstractTask.getEndTime() != null && abstractTask.getStartTime() != null) {
                if (abstractTask.getEndTime().equals(abstractTask.getStartTime())) {
                    // If the task is a Milestone
                    task.setStartTime(abstractTask.getEndTime());
                    task.setEndTime(Instant.ofEpochSecond(2 * abstractTask.getEndTime().getEpochSecond() - abstractTask.getStartTime().getEpochSecond()));
                } else {
                    task.setStartTime(abstractTask.getEndTime().plus(1, ChronoUnit.MINUTES));
                    task.setEndTime(Instant.ofEpochSecond(2 * abstractTask.getEndTime().getEpochSecond() - abstractTask.getStartTime().getEpochSecond()).plus(1, ChronoUnit.MINUTES));
                }
            }

            EObject parent = context.eContainer();
            if (parent instanceof Workpackage workpackage) {
                int index = workpackage.getOwnedTasks().indexOf(context);
                workpackage.getOwnedTasks().add(index + 1, task);
            } else if (parent instanceof AbstractTask parentTask) {
                int index = parentTask.getSubTasks().indexOf(context);
                parentTask.getSubTasks().add(index + 1, task);
            }
        } else if (context instanceof Workpackage workpackage) {
            long epochSecondStartTime = Instant.now().getEpochSecond();
            task.setStartTime(Instant.ofEpochMilli(epochSecondStartTime));
            task.setEndTime(Instant.ofEpochMilli(epochSecondStartTime + 3600 * 4));

            workpackage.getOwnedTasks().add(task);
        }
    }

    public void deleteTask(EObject context) {
        if (context instanceof Task sourceTask) {
            deleteTasksRecursive(sourceTask);
            EcoreUtil.delete(sourceTask, true);
        }
    }

    private void deleteTasksRecursive(Task task) {

        Collection<EStructuralFeature.Setting> inverseReferences = simpleCrossReferenceProvider.getInverseReferences(task);
        for (EStructuralFeature.Setting inverseReference : inverseReferences) {
            if (inverseReference.getEObject() instanceof DependencyLink dep) {
                EcoreUtil.delete(dep, true);
            }
        }
        for (Task subTask : task.getSubTasks()) {
            this.deleteTasksRecursive(subTask);
        }
    }

    public void deleteDependencyLink(EObject target, EObject source) {
        if (target instanceof Task targetTask) {
            if (source instanceof Task sourceTask) {
                targetTask.getDependencies().removeIf(dep -> (dep.getSource() instanceof Task dependency) && dependency.equals(sourceTask));
            }
        }
    }


    public void createDependencyLink(EObject target, EObject source, org.eclipse.sirius.components.gantt.StartOrEnd sourceStartOrEnd, org.eclipse.sirius.components.gantt.StartOrEnd targetStartOrEnd) {
        DependencyLink dependencyLink = PepperFactory.eINSTANCE.createDependencyLink();
        if (sourceStartOrEnd.equals(org.eclipse.sirius.components.gantt.StartOrEnd.END)) {
            dependencyLink.setSourceKind(StartOrEnd.END);
        } else {
            dependencyLink.setSourceKind(StartOrEnd.START);
        }
        if (targetStartOrEnd.equals(org.eclipse.sirius.components.gantt.StartOrEnd.START)) {
            dependencyLink.setTargetKind(StartOrEnd.START);
        } else {
            dependencyLink.setTargetKind(StartOrEnd.END);
        }
        if (source instanceof Task sourceTask) {
            dependencyLink.setSource(sourceTask);
            if (target instanceof Task targetTask) {
                //Ensure no dependency already exists between source and target to prevent duplicates or cycles
                if (!isDuplicateOrCycle(sourceTask, targetTask)) {
                    targetTask.getDependencies().add(dependencyLink);
                    this.followTaskMoveDependency(sourceTask);
                } else {
                    this.feedbackMessageService.addFeedbackMessage(new Message("Creating a dependency that is duplicate or cyclic is not possible.", MessageLevel.ERROR));
                }
            }
        }
    }

    private static boolean isCycle(Task sourceTask, Task targetTask) {
        boolean isCycle = false;
        for (DependencyLink dep : sourceTask.getDependencies()) {
            Task sourceDependency = (Task) dep.getSource();
            if (sourceDependency.equals(targetTask)) {
                isCycle = true;
            } else if (!isCycle) {
                isCycle = isCycle(sourceDependency, targetTask);
            }
        }
        return isCycle;
    }

    private static boolean isDuplicateOrCycle(Task sourceTask, Task targetTask) {
        //to prevent cycles
        boolean isCycle = isCycle(sourceTask, targetTask);
        //to prevent duplicates
        boolean isDuplicate = false;
        for (DependencyLink dep : targetTask.getDependencies()) {
            if (dep.getSource().equals(sourceTask)) {
                isDuplicate = true;
                break;
            }
        }
        return isDuplicate || isCycle;
    }

    private void followTaskMoveDependency(Task sourceTask) {
        List<Task> dependencies = new ArrayList<>();
        List<Task> targetTasks = new ArrayList<>();
        //get all tasks pointed by sourceTask
        for (var inverseReference : simpleCrossReferenceProvider.getInverseReferences(sourceTask)) {
            if (inverseReference.getEObject() instanceof DependencyLink dep) {
                for (var inverseReferenceDependencyLink : simpleCrossReferenceProvider.getInverseReferences(dep)) {
                    if (inverseReferenceDependencyLink.getEObject() instanceof Task targetTask) {
                        targetTasks.add(targetTask);
                    }
                }
            }
        }
        for (Task task : targetTasks) {
            //Get the strongest dependency link
            DependencyLink winner = null;
            Instant latterInstant = null;
            for (DependencyLink dep : task.getDependencies()) {
                Instant newInstant = getLatterInstant(dep);
                if (latterInstant == null || latterInstant.compareTo(newInstant) < 0) {
                    latterInstant = newInstant;
                    winner = dep;
                }
            }
            for (DependencyLink dep : task.getDependencies()) {
                if (dep.equals(winner)) {
                    Task bestSourceTask = (Task) dep.getSource();
                    setTaskNewDates(task, dep);
                    if (bestSourceTask == sourceTask) {
                        dependencies.add(task);
                    }
                }
            }
        }
        for (Task task : dependencies) {
            followTaskMoveDependency(task);
        }
    }

    private void setTaskNewDates(Task task, DependencyLink dep) {
        Task bestSourceTask = (Task) dep.getSource();
        Instant sourceStart = bestSourceTask.getStartTime();
        Instant sourceEnd = bestSourceTask.getEndTime();
        Instant oldTaskStart = task.getStartTime();
        Instant oldTaskEnd = task.getEndTime();
        int delay = dep.getDuration();
        StartOrEnd sourceStartOrEnd = dep.getSourceKind();
        StartOrEnd targetStartOrEnd = dep.getTargetKind();
        int zeroIfSourceMilestone = 1;
        int oneIfSourceMilestone = 0;
        if (sourceStart.equals(sourceEnd)) {
            zeroIfSourceMilestone = 0;
            oneIfSourceMilestone = 1;
        }
        int oneIfTargetMilestone = 0;
        if (oldTaskEnd.equals(oldTaskStart)) {
            oneIfTargetMilestone = 1;
        }
        if (sourceStartOrEnd == StartOrEnd.END && targetStartOrEnd == StartOrEnd.START) {
            Instant newTaskStart = sourceEnd.plus(delay, ChronoUnit.HOURS).plus(zeroIfSourceMilestone, ChronoUnit.MINUTES);
            Instant newTaskEnd = Instant.ofEpochSecond(newTaskStart.getEpochSecond() + oldTaskEnd.getEpochSecond() - oldTaskStart.getEpochSecond());
            task.setEndTime(newTaskEnd);
            task.setStartTime(newTaskStart);
        } else if (sourceStartOrEnd == StartOrEnd.START && targetStartOrEnd == StartOrEnd.START) {
            Instant newTaskStart = sourceStart.plus(delay, ChronoUnit.HOURS);
            Instant newTaskEnd = Instant.ofEpochSecond(newTaskStart.getEpochSecond() + oldTaskEnd.getEpochSecond() - oldTaskStart.getEpochSecond());
            task.setEndTime(newTaskEnd);
            task.setStartTime(newTaskStart);
        } else if (sourceStartOrEnd == StartOrEnd.END && targetStartOrEnd == StartOrEnd.END) {
            Instant newTaskEnd = sourceEnd.plus(delay, ChronoUnit.HOURS).minus(oneIfSourceMilestone, ChronoUnit.MINUTES).plus(oneIfTargetMilestone, ChronoUnit.MINUTES);
            Instant newTaskStart = Instant.ofEpochSecond(newTaskEnd.getEpochSecond() - (oldTaskEnd.getEpochSecond() - oldTaskStart.getEpochSecond()));
            task.setEndTime(newTaskEnd);
            task.setStartTime(newTaskStart);
        } else if (sourceStartOrEnd == StartOrEnd.START && targetStartOrEnd == StartOrEnd.END) {
            Instant newTaskEnd = sourceStart.plus(delay, ChronoUnit.HOURS).minus(1, ChronoUnit.MINUTES).plus(oneIfTargetMilestone, ChronoUnit.MINUTES);
            Instant newTaskStart = Instant.ofEpochSecond(newTaskEnd.getEpochSecond() - (oldTaskEnd.getEpochSecond() - oldTaskStart.getEpochSecond()));
            task.setEndTime(newTaskEnd);
            task.setStartTime(newTaskStart);
        }
    }

    private static Instant getLatterInstant(DependencyLink dep) {
        Instant laterInstant = null;
        Task source = (Task) dep.getSource();
        if (dep.getSourceKind() == StartOrEnd.END) {
            laterInstant = source.getEndTime().plus(dep.getDuration(), ChronoUnit.HOURS);
        } else if (dep.getSourceKind() == StartOrEnd.START) {
            laterInstant = source.getStartTime().plus(dep.getDuration(), ChronoUnit.HOURS);
        }
        return laterInstant;
    }
}
