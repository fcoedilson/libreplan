/*
 * This file is part of NavalPlan
 *
 * Copyright (C) 2009 Fundación para o Fomento da Calidade Industrial e
 *                    Desenvolvemento Tecnolóxico de Galicia
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.navalplanner.web.limitingresources;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

import org.apache.commons.lang.Validate;
import org.hibernate.Hibernate;
import org.hibernate.proxy.HibernateProxy;
import org.joda.time.LocalDate;
import org.joda.time.Period;
import org.navalplanner.business.calendars.entities.BaseCalendar;
import org.navalplanner.business.calendars.entities.CalendarAvailability;
import org.navalplanner.business.calendars.entities.CalendarData;
import org.navalplanner.business.calendars.entities.CalendarException;
import org.navalplanner.business.common.exceptions.InstanceNotFoundException;
import org.navalplanner.business.orders.daos.IOrderElementDAO;
import org.navalplanner.business.orders.entities.Order;
import org.navalplanner.business.orders.entities.OrderElement;
import org.navalplanner.business.planner.daos.IDependencyDAO;
import org.navalplanner.business.planner.daos.ITaskElementDAO;
import org.navalplanner.business.planner.entities.DayAssignment;
import org.navalplanner.business.planner.entities.Dependency;
import org.navalplanner.business.planner.entities.GenericResourceAllocation;
import org.navalplanner.business.planner.entities.ResourceAllocation;
import org.navalplanner.business.planner.entities.SpecificResourceAllocation;
import org.navalplanner.business.planner.entities.Task;
import org.navalplanner.business.planner.entities.TaskElement;
import org.navalplanner.business.planner.limiting.daos.ILimitingResourceQueueDAO;
import org.navalplanner.business.planner.limiting.daos.ILimitingResourceQueueDependencyDAO;
import org.navalplanner.business.planner.limiting.daos.ILimitingResourceQueueElementDAO;
import org.navalplanner.business.planner.limiting.entities.DateAndHour;
import org.navalplanner.business.planner.limiting.entities.LimitingResourceAllocator;
import org.navalplanner.business.planner.limiting.entities.LimitingResourceQueueDependency;
import org.navalplanner.business.planner.limiting.entities.LimitingResourceQueueElement;
import org.navalplanner.business.planner.limiting.entities.LimitingResourceQueueElementGap;
import org.navalplanner.business.resources.entities.Criterion;
import org.navalplanner.business.resources.entities.CriterionCompounder;
import org.navalplanner.business.resources.entities.CriterionSatisfaction;
import org.navalplanner.business.resources.entities.ICriterion;
import org.navalplanner.business.resources.entities.LimitingResourceQueue;
import org.navalplanner.business.resources.entities.Resource;
import org.navalplanner.business.users.daos.IOrderAuthorizationDAO;
import org.navalplanner.business.users.daos.IUserDAO;
import org.navalplanner.business.users.entities.OrderAuthorization;
import org.navalplanner.business.users.entities.OrderAuthorizationType;
import org.navalplanner.business.users.entities.User;
import org.navalplanner.business.users.entities.UserRole;
import org.navalplanner.web.security.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.zkoss.ganttz.timetracker.zoom.ZoomLevel;
import org.zkoss.ganttz.util.Interval;

@Component
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class LimitingResourceQueueModel implements ILimitingResourceQueueModel {

    @Autowired
    private IOrderElementDAO orderElementDAO;

    @Autowired
    private IUserDAO userDAO;

    @Autowired
    private IOrderAuthorizationDAO orderAuthorizationDAO;

    @Autowired
    private ILimitingResourceQueueElementDAO limitingResourceQueueElementDAO;

    @Autowired
    private ILimitingResourceQueueDAO limitingResourceQueueDAO;

    @Autowired
    private ITaskElementDAO taskDAO;

    @Autowired
    private ILimitingResourceQueueDependencyDAO limitingResourceQueueDependencyDAO;

    private Interval viewInterval;

    private List<LimitingResourceQueue> limitingResourceQueues = new ArrayList<LimitingResourceQueue>();

    private List<LimitingResourceQueueElement> unassignedLimitingResourceQueueElements = new ArrayList<LimitingResourceQueueElement>();

    private LimitingResourceQueueElement beingEdited;

    private Set<LimitingResourceQueueElement> toBeRemoved = new HashSet<LimitingResourceQueueElement>();

    private Set<LimitingResourceQueueElement> toBeSaved = new HashSet<LimitingResourceQueueElement>();

    private ZoomLevel zoomLevel = ZoomLevel.DETAIL_THREE;

    @Override
    @Transactional(readOnly = true)
    public void initGlobalView(boolean filterByResources) {
        doGlobalView();
    }

    @Override
    @Transactional(readOnly = true)
    public void initGlobalView(Order filterBy, boolean filterByResources) {
        doGlobalView();
    }

    private void doGlobalView() {
        loadUnassignedLimitingResourceQueueElements();
        loadLimitingResourceQueues();
        final Date startingDate = getEarliestDate();
        Date endDate = (new LocalDate(startingDate)).plus(intervalIncrease())
                .toDateTimeAtCurrentTime().toDate();
        viewInterval = new Interval(startingDate, endDate);
    }

    private Period intervalIncrease() {

        switch (zoomLevel) {
        case DETAIL_ONE:
            return Period.years(5);
        case DETAIL_TWO:
            return Period.years(5);
        case DETAIL_THREE:
            return Period.years(2);
        case DETAIL_FOUR:
            return Period.months(6);
        case DETAIL_FIVE:
            return Period.weeks(6);
        case DETAIL_SIX:
            return Period.weeks(6);
        }
        return Period.years(1);
    }

    private Date getEarliestDate() {
        final LimitingResourceQueueElement element = getEarliestQueueElement();
        return (element != null) ? element.getStartDate()
                .toDateTimeAtCurrentTime().toDate() : new Date();
    }

    private LimitingResourceQueueElement getEarliestQueueElement() {
        LimitingResourceQueueElement earliestQueueElement = null;

        if (!limitingResourceQueues.isEmpty()) {
            for (LimitingResourceQueue each : limitingResourceQueues) {
                LimitingResourceQueueElement element = getFirstLimitingResourceQueueElement(each);
                if (element == null) {
                    continue;
                }
                if (earliestQueueElement == null
                        || isEarlier(element, earliestQueueElement)) {
                    earliestQueueElement = element;
                }
            }
        }
        return earliestQueueElement;
    }

    private boolean isEarlier(LimitingResourceQueueElement arg1,
            LimitingResourceQueueElement arg2) {
        return (arg1.getStartDate().isBefore(arg2.getStartDate()));
    }

    private LimitingResourceQueueElement getFirstLimitingResourceQueueElement(
            LimitingResourceQueue queue) {
        return getFirstChild(queue.getLimitingResourceQueueElements());
    }

    private LimitingResourceQueueElement getFirstChild(
            SortedSet<LimitingResourceQueueElement> elements) {
        return (elements.isEmpty()) ? null : elements.iterator().next();
    }

    /**
     * Loads unassigned {@link LimitingResourceQueueElement} from DB
     *
     * @return
     */
    private void loadUnassignedLimitingResourceQueueElements() {
        unassignedLimitingResourceQueueElements.clear();
        unassignedLimitingResourceQueueElements
                .addAll(initializeLimitingResourceQueueElements(limitingResourceQueueElementDAO
                        .getUnassigned()));
    }

    private List<LimitingResourceQueueElement> initializeLimitingResourceQueueElements(
            List<LimitingResourceQueueElement> elements) {
        for (LimitingResourceQueueElement each : elements) {
            initializeLimitingResourceQueueElement(each);
        }
        return elements;
    }

    private void initializeLimitingResourceQueueElement(
            LimitingResourceQueueElement element) {
        ResourceAllocation<?> resourceAllocation = element
                .getResourceAllocation();
        resourceAllocation = initializeResourceAllocationIfNecessary(resourceAllocation);
        element.setResourceAllocation(resourceAllocation);
        initializeTask(resourceAllocation.getTask());
        initializeResourceIfAny(element.getResource());
    }

    private void initializeTask(Task task) {
        Hibernate.initialize(task);
        for (ResourceAllocation<?> each: task.getAllResourceAllocations()) {
            Hibernate.initialize(each);
        }
        for (Dependency each: task.getDependenciesWithThisOrigin()) {
            Hibernate.initialize(each);
        }
        for (Dependency each: task.getDependenciesWithThisDestination()) {
            Hibernate.initialize(each);
        }
        initializeRootOrder(task);
    }

    // FIXME: Needed to fetch order.name in QueueComponent.composeTooltiptext.
    // Try to replace it with a HQL query instead of iterating all the way up
    // through order
    private void initializeRootOrder(Task task) {
        Hibernate.initialize(task.getOrderElement());
        OrderElement order = task.getOrderElement();
        do {
            Hibernate.initialize(order.getParent());
            order = order.getParent();
        } while (order.getParent() != null);
    }

    private void initializeCalendarIfAny(BaseCalendar calendar) {
        if (calendar != null) {
            Hibernate.initialize(calendar);
            initializeCalendarAvailabilities(calendar);
            initializeCalendarExceptions(calendar);
            initializeCalendarDataVersions(calendar);
        }
    }

    private void initializeCalendarAvailabilities(BaseCalendar calendar) {
        for (CalendarAvailability each : calendar.getCalendarAvailabilities()) {
            Hibernate.initialize(each);
        }
    }

    private void initializeCalendarExceptions(BaseCalendar calendar) {
        for (CalendarException each : calendar.getExceptions()) {
            Hibernate.initialize(each);
            Hibernate.initialize(each.getType());
        }
    }

    private void initializeCalendarDataVersions(BaseCalendar calendar) {
        for (CalendarData each : calendar.getCalendarDataVersions()) {
            Hibernate.initialize(each);
            Hibernate.initialize(each.getHoursPerDay());
            initializeCalendarIfAny(each.getParent());
        }
    }

    private ResourceAllocation<?> initializeResourceAllocationIfNecessary(
            ResourceAllocation<?> resourceAllocation) {
        if (resourceAllocation instanceof HibernateProxy) {
            resourceAllocation = (ResourceAllocation<?>) ((HibernateProxy) resourceAllocation)
                    .getHibernateLazyInitializer().getImplementation();
            if (resourceAllocation instanceof GenericResourceAllocation) {
                GenericResourceAllocation generic = (GenericResourceAllocation) resourceAllocation;
                initializeCriteria(generic.getCriterions());
            }
            Hibernate.initialize(resourceAllocation.getAssignments());
            Hibernate.initialize(resourceAllocation.getLimitingResourceQueueElement());
        }
        return resourceAllocation;
    }

    private void initializeCriteria(Set<Criterion> criteria) {
        for (Criterion each: criteria) {
            initializeCriterion(each);
        }
    }

    private void initializeCriterion(Criterion criterion) {
        Hibernate.initialize(criterion);
        Hibernate.initialize(criterion.getType());
    }

    private void loadLimitingResourceQueues() {
        limitingResourceQueues.clear();
        limitingResourceQueues
                .addAll(initializeLimitingResourceQueues(limitingResourceQueueDAO
                        .getAll()));
    }

    private List<LimitingResourceQueue> initializeLimitingResourceQueues(
            List<LimitingResourceQueue> queues) {
        for (LimitingResourceQueue each : queues) {
            initializeLimitingResourceQueue(each);
        }
        return queues;
    }

    private void initializeLimitingResourceQueue(LimitingResourceQueue queue) {
        initializeResourceIfAny(queue.getResource());
        for (LimitingResourceQueueElement each : queue
                .getLimitingResourceQueueElements()) {
            initializeLimitingResourceQueueElement(each);
        }
    }

    private void initializeResourceIfAny(Resource resource) {
        if (resource != null) {
            Hibernate.initialize(resource);
            initializeCalendarIfAny(resource.getCalendar());
            for (CriterionSatisfaction each : resource
                    .getCriterionSatisfactions()) {
                Hibernate.initialize(each);
                initializeCriterion(each.getCriterion());
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Order getOrderByTask(TaskElement task) {
        return orderElementDAO
                .loadOrderAvoidingProxyFor(task.getOrderElement());
    }

    @Override
    public Interval getViewInterval() {
        return viewInterval;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean userCanRead(Order order, String loginName) {
        if (SecurityUtils.isUserInRole(UserRole.ROLE_READ_ALL_ORDERS)
                || SecurityUtils.isUserInRole(UserRole.ROLE_EDIT_ALL_ORDERS)) {
            return true;
        }
        try {
            User user = userDAO.findByLoginName(loginName);
            for (OrderAuthorization authorization : orderAuthorizationDAO
                    .listByOrderUserAndItsProfiles(order, user)) {
                if (authorization.getAuthorizationType() == OrderAuthorizationType.READ_AUTHORIZATION
                        || authorization.getAuthorizationType() == OrderAuthorizationType.WRITE_AUTHORIZATION) {
                    return true;
                }
            }
        } catch (InstanceNotFoundException e) {
            // this case shouldn't happen, because it would mean that there
            // isn't a logged user
            // anyway, if it happenned we don't allow the user to pass
        }
        return false;
    }

    @Override
    public List<LimitingResourceQueue> getLimitingResourceQueues() {
        return Collections.unmodifiableList(limitingResourceQueues);
    }

    public List<LimitingResourceQueueElement> getUnassignedLimitingResourceQueueElements() {
        return Collections
                .unmodifiableList(unassignedLimitingResourceQueueElements);
    }

    public ZoomLevel calculateInitialZoomLevel() {
        Interval interval = getViewInterval();
        return ZoomLevel.getDefaultZoomByDates(new LocalDate(interval
                .getStart()), new LocalDate(interval.getFinish()));
    }

    @Override
    public boolean assignLimitingResourceQueueElement(
            LimitingResourceQueueElement element) {

        LimitingResourceQueue queue = null;
        DateAndHour startTime = null;

        LimitingResourceQueueElement queueElement = retrieveQueueElementFromModel(element);

        final ResourceAllocation<?> resourceAllocation = queueElement
                .getResourceAllocation();
        if (resourceAllocation instanceof SpecificResourceAllocation) {
            // Retrieve queue
            queue = retrieveQueueByResourceFromModel(queueElement.getResource());
            // Set start time
            final LimitingResourceQueueElementGap firstGap = LimitingResourceAllocator
                    .getFirstValidGap(queue, queueElement);
            startTime = firstGap.getStartTime();
        } else if (resourceAllocation instanceof GenericResourceAllocation) {
            // Get the first gap for all the queues that can allocate the
            // element during a certain interval of time
            Map<LimitingResourceQueueElementGap, LimitingResourceQueue> firstGapsForQueues = findFirstGapsInAllQueues(
                    limitingResourceQueues, element);
            // Among those queues, get the earliest gap
            LimitingResourceQueueElementGap earliestGap = findEarliestGap(firstGapsForQueues
                    .keySet());
            if (earliestGap == null) {
                return false;
            }
            // Select queue and start time
            queue = firstGapsForQueues.get(earliestGap);
            startTime = earliestGap.getStartTime();
        }
        return assignLimitingResourceQueueElementToQueueAt(queueElement, queue,
                startTime, getEndsAfterBecauseOfGantt(queueElement));
    }

    private DateAndHour getEndsAfterBecauseOfGantt(
            LimitingResourceQueueElement queueElement) {
        return DateAndHour.from(LocalDate.fromDateFields(queueElement
                        .getEarliestEndDateBecauseOfGantt()));
    }

    private boolean assignLimitingResourceQueueElementToQueueAt(
            LimitingResourceQueueElement element, LimitingResourceQueue queue,
            DateAndHour startTime, DateAndHour endsAfter) {

        // Allocate day assignments and adjust start and end times for element
        List<DayAssignment> dayAssignments = LimitingResourceAllocator
                .generateDayAssignments(element.getResourceAllocation(), queue
                        .getResource(), startTime, endsAfter);
        element.getResourceAllocation().allocateLimitingDayAssignments(
                dayAssignments);

        DateAndHour endTime = LimitingResourceAllocator
                .getLastElementTime(dayAssignments);
        // the assignments can be generated after the required start
        startTime = DateAndHour.Max(startTime, startFor(dayAssignments));
        if (sameDay(startTime, endTime)) {
            endTime = new DateAndHour(endTime.getDate(), startTime.getHour() + endTime.getHour());
        }
        updateStartAndEndTimes(element, startTime, endTime);

        // Add element to queue
        addLimitingResourceQueueElement(queue, element);
        markAsModified(element);
        return true;
    }

    private DateAndHour startFor(List<DayAssignment> dayAssignments) {
        return new DateAndHour(dayAssignments
                .get(0).getDay(), 0);
    }

    private boolean sameDay(DateAndHour startTime, DateAndHour endTime) {
        return startTime.getDate().equals(endTime.getDate());
    }

    private LimitingResourceQueueElementGap findEarliestGap(Set<LimitingResourceQueueElementGap> gaps) {
        LimitingResourceQueueElementGap earliestGap = null;
        for (LimitingResourceQueueElementGap each: gaps) {
            if (earliestGap == null || each.isBefore(earliestGap)) {
                earliestGap = each;
            }
        }
        return earliestGap;
    }

    private Map<LimitingResourceQueueElementGap, LimitingResourceQueue> findFirstGapsInAllQueues(
            List<LimitingResourceQueue> queues,
            LimitingResourceQueueElement element) {

        Map<LimitingResourceQueueElementGap, LimitingResourceQueue> result = new HashMap<LimitingResourceQueueElementGap, LimitingResourceQueue>();

        for (LimitingResourceQueue each : queues) {
            LimitingResourceQueueElementGap gap = LimitingResourceAllocator
                    .getFirstValidGap(each, element);
            if (gap != null) {
                result.put(gap, each);
            }
        }
        return result;
    }

    private void markAsModified(LimitingResourceQueueElement element) {
        if (!toBeSaved.contains(element)) {
            toBeSaved.add(element);
        }
    }

    public LimitingResourceQueueElementGap createGap(Resource resource, DateAndHour startTime,
            DateAndHour endTime) {
        return LimitingResourceQueueElementGap.create(resource, startTime, endTime);
    }

    private void updateStartAndEndTimes(LimitingResourceQueueElement element,
            DateAndHour startTime, DateAndHour endTime) {

        element.setStartDate(startTime.getDate());
        element.setStartHour(startTime.getHour());
        element.setEndDate(endTime.getDate());
        element.setEndHour(endTime.getHour());

        // Update starting and ending dates for associated Task
        Task task = element.getResourceAllocation().getTask();
        updateStartingAndEndingDate(task, startTime.getDate(), endTime
                .getDate());
    }

    private void updateStartingAndEndingDate(Task task, LocalDate startDate,
            LocalDate endDate) {
        task.setStartDate(toDate(startDate));
        task.setEndDate(endDate.toDateTimeAtStartOfDay().toDate());
        task.explicityMoved(toDate(startDate));
    }

    private Date toDate(LocalDate date) {
        return date.toDateTimeAtStartOfDay().toDate();
    }

    private void addLimitingResourceQueueElement(LimitingResourceQueue queue,
            LimitingResourceQueueElement element) {
        queue.addLimitingResourceQueueElement(element);
        unassignedLimitingResourceQueueElements.remove(element);
    }

    private LimitingResourceQueue retrieveQueueByResourceFromModel(Resource resource) {
        return findQueueByResource(limitingResourceQueues, resource);
    }

    private LimitingResourceQueue findQueueByResource(
            List<LimitingResourceQueue> queues, Resource resource) {
        for (LimitingResourceQueue each : queues) {
            if (each.getResource().getId().equals(resource.getId())) {
                return each;
            }
        }
        return null;
    }

    private LimitingResourceQueue retrieveQueueFromModel(LimitingResourceQueue queue) {
        return findQueue(limitingResourceQueues, queue);
    }

    private LimitingResourceQueue findQueue(List<LimitingResourceQueue> queues,
            LimitingResourceQueue queue) {
        for (LimitingResourceQueue each : limitingResourceQueues) {
            if (each.getId().equals(queue.getId())) {
                return each;
            }
        }
        return null;
    }

    private LimitingResourceQueueElement retrieveQueueElementFromModel(
            LimitingResourceQueueElement element) {
        final LimitingResourceQueue queue = element.getLimitingResourceQueue();
        if (queue != null) {
            return findQueueElement(queue.getLimitingResourceQueueElements(),
                    element);
        } else {
            return findQueueElement(unassignedLimitingResourceQueueElements,
                    element);
        }
    }

    private LimitingResourceQueueElement findQueueElement(
            Collection<LimitingResourceQueueElement> elements,
            LimitingResourceQueueElement element) {
        for (LimitingResourceQueueElement each : elements) {
            if (each.getId().equals(element.getId())) {
                return each;
            }
        }
        return null;
    }

    @Override
    @Transactional
    public void confirm() {
        applyChanges();
    }

    private void applyChanges() {
        removeQueueElements();
        saveQueueElements();
        taskDAO.removeOrphanedDayAssignments();
    }

    private void saveQueueElements() {
        for (LimitingResourceQueueElement each: toBeSaved) {
            if (each != null) {
                saveQueueElement(each);
            }
        }
        toBeSaved.clear();
    }

    private void saveQueueElement(LimitingResourceQueueElement element) {
        limitingResourceQueueElementDAO.save(element);
        taskDAO.save(getAssociatedTask(element));
    }

    private Task getAssociatedTask(LimitingResourceQueueElement element) {
        return element.getResourceAllocation().getTask();
    }

    private void removeQueueElements() {
        for (LimitingResourceQueueElement each: toBeRemoved) {
            removeQueueElement(each);
        }
        toBeRemoved.clear();
    }

    private void removeQueueElement(LimitingResourceQueueElement element) {
        Task task = getAssociatedTask(element);
        removeQueueDependenciesIfAny(task);
        removeQueueElementById(element.getId());
    }

    private void removeQueueElementById(Long id) {
        try {
            limitingResourceQueueElementDAO.remove(id);
        } catch (InstanceNotFoundException e) {
            e.printStackTrace();
            throw new RuntimeException("Trying to remove non-existing entity");
        }
    }

    private void removeQueueDependenciesIfAny(Task task) {
        for (Dependency each: task.getDependenciesWithThisOrigin()) {
            removeQueueDependencyIfAny(each);
        }
        for (Dependency each: task.getDependenciesWithThisDestination()) {
            removeQueueDependencyIfAny(each);
        }
    }

    private void removeQueueDependencyIfAny(Dependency dependency) {
        LimitingResourceQueueDependency queueDependency = dependency.getQueueDependency();
        if (queueDependency != null) {
            queueDependency.getHasAsOrigin().remove(queueDependency);
            queueDependency.getHasAsDestiny().remove(queueDependency);
            dependency.setQueueDependency(null);
            dependencyDAO.save(dependency);
            removeQueueDependencyById(queueDependency.getId());
        }
    }

    @Autowired
    private IDependencyDAO dependencyDAO;

    private void removeQueueDependencyById(Long id) {
        try {
            limitingResourceQueueDependencyDAO.remove(id);
        } catch (InstanceNotFoundException e) {
            e.printStackTrace();
            throw new RuntimeException("Trying to remove non-existing entity");
        }
    }

    /**
     * Unschedules an element from the list of queue elements. The element is
     * later added to the list of unassigned elements
     */
    @Override
    public void unschedule(LimitingResourceQueueElement element) {
        LimitingResourceQueueElement queueElement = retrieveQueueElementFromModel(element);
        LimitingResourceQueue queue = retrieveQueueFromModel(element.getLimitingResourceQueue());

        queue.removeLimitingResourceQueueElement(queueElement);

        // Set as unassigned element
        queueElement.setLimitingResourceQueue(null);
        queueElement.setStartDate(null);
        queueElement.setStartHour(0);
        queueElement.setEndDate(null);
        queueElement.setEndHour(0);

        queueElement.getResourceAllocation().removeLimitingDayAssignments();
        unassignedLimitingResourceQueueElements.add(queueElement);
        markAsModified(queueElement);
    }

    /**
     * Removes an {@link LimitingResourceQueueElement} from the list of
     * unassigned elements
     *
     */
    @Override
    public void removeUnassignedLimitingResourceQueueElement(
            LimitingResourceQueueElement element) {
        LimitingResourceQueueElement queueElement = retrieveQueueElementFromModel(element);

        queueElement.getResourceAllocation().setLimitingResourceQueueElement(null);
        queueElement.getResourceAllocation().getTask()
                .removeAllResourceAllocations();
        unassignedLimitingResourceQueueElements.remove(queueElement);
        markAsRemoved(queueElement);
    }

    private void markAsRemoved(LimitingResourceQueueElement element) {
        if (toBeSaved.contains(element)) {
            toBeSaved.remove(element);
        }
        if (!toBeRemoved.contains(element)) {
            toBeRemoved.add(element);
        }
    }

    @Override
    public void setTimeTrackerState(ZoomLevel timeTrackerState) {
        this.zoomLevel = timeTrackerState;
    }

    @Override
    public List<LimitingResourceQueue> getAssignableQueues(
            LimitingResourceQueueElement element) {

        final ResourceAllocation<?> resourceAllocation = element.getResourceAllocation();
        if (resourceAllocation instanceof SpecificResourceAllocation) {
            LimitingResourceQueue queue = retrieveQueueByResourceFromModel(element.getResource());
            Validate.notNull(queue);
            return Collections.singletonList(queue);
        } else if (resourceAllocation instanceof GenericResourceAllocation) {
            final GenericResourceAllocation generic = (GenericResourceAllocation) element.getResourceAllocation();
            return findQueuesMatchingCriteria(limitingResourceQueues, generic.getCriterions());
        }
        return null;
    }

    private List<LimitingResourceQueue> findQueuesMatchingCriteria(List<LimitingResourceQueue> queues, Set<Criterion> criteria) {
        List<LimitingResourceQueue> result = new ArrayList<LimitingResourceQueue>();

        final ICriterion compositedCriterion = CriterionCompounder.buildAnd(criteria).getResult();
        for (LimitingResourceQueue each: queues) {
            if (compositedCriterion.isSatisfiedBy(each.getResource())) {
                result.add(each);
            }
        }
        return result;
    }

    @Override
    public boolean nonAppropriativeAllocation(
            LimitingResourceQueueElement element,
            LimitingResourceQueue queue,
            DateAndHour startTime) {

        Validate.notNull(element);
        Validate.notNull(queue);
        Validate.notNull(startTime);

        LimitingResourceQueue currentQueue = element.getLimitingResourceQueue();
        if (currentQueue != null) {
            removeDayAssignments(element);
            removeFromQueue(currentQueue, element);
        }
        return assignLimitingResourceQueueElementToQueueAt(element, queue,
                startTime, getEndsAfterBecauseOfGantt(element));
    }

    private void removeDayAssignments(LimitingResourceQueueElement element) {
        final ResourceAllocation<?> resourceAllocation = element.getResourceAllocation();
        resourceAllocation.removeLimitingDayAssignments();
    }

    private void removeFromQueue(LimitingResourceQueue queue, LimitingResourceQueueElement element) {
        queue.removeLimitingResourceQueueElement(element);
        element.setLimitingResourceQueue(null);
    }

    @Override
    public void init(LimitingResourceQueueElement element) {
        beingEdited = retrieveQueueElementFromModel(element);
    }

    @Override
    public LimitingResourceQueueElement getLimitingResourceQueueElement() {
        return beingEdited;
    }

    @Override
    public void appropriativeAllocation(LimitingResourceQueueElement _element, LimitingResourceQueue _queue,
            DateAndHour allocationTime) {

        LimitingResourceQueue queue = retrieveQueueFromModel(_queue);
        LimitingResourceQueueElement element = retrieveQueueElementFromModel(_element);

        if (element.getLimitingResourceQueue() != null) {
            unschedule(element);
        }

        List<LimitingResourceQueueElement> unscheduledElements = new ArrayList<LimitingResourceQueueElement>();

        LimitingResourceQueueElementGap gap;
        do {
            gap = LimitingResourceAllocator.getFirstValidGapSince(element, queue, allocationTime);

            if (gap != null) {
                final LocalDate startDate = gap.getStartTime().getDate();

                if (startDate.equals(allocationTime.getDate())) {
                    assignLimitingResourceQueueElementToQueueAt(element, queue,
                            allocationTime, getEndsAfterBecauseOfGantt(element));
                    break;
                } else {
                    LimitingResourceQueueElement elementAtTime = getFirstElementFrom(
                            queue, allocationTime);
                    if (elementAtTime != null) {
                        unschedule(elementAtTime);
                        unscheduledElements.add(elementAtTime);
                    }
                }
            }
        } while (gap != null);

        for (LimitingResourceQueueElement each: unscheduledElements) {
            gap = LimitingResourceAllocator.getFirstValidGap(queue, each);
            assignLimitingResourceQueueElementToQueueAt(each, queue, gap
                    .getStartTime(), getEndsAfterBecauseOfGantt(element));
        }

    }

    @SuppressWarnings("unchecked")
    public LimitingResourceQueueElement getFirstElementFrom(LimitingResourceQueue queue, DateAndHour allocationTime) {
        final List<LimitingResourceQueueElement> elements = new ArrayList(queue.getLimitingResourceQueueElements());

        // First element
        final LimitingResourceQueueElement first = elements.get(0);
        if (isAfter(first, allocationTime)) {
            return first;
        }

        // Rest of elements
        for (int i = 0; i < elements.size(); i++) {
            final LimitingResourceQueueElement each = elements.get(i);
            if (isInTheMiddle(each, allocationTime) ||
                    isAfter(each, allocationTime)) {
                return each;
            }
        }
        return null;
    }

    private boolean isAfter(LimitingResourceQueueElement element, DateAndHour time) {
        return element.getStartTime().isAfter(time);
    }

    private boolean isInTheMiddle(LimitingResourceQueueElement element, DateAndHour time) {
        return (element.getStartTime().isBefore(time) || element.getStartTime().isEquals(time))
                    && (element.getEndTime().isAfter(time) || element.getEndTime().isEquals(time));
    }

}
