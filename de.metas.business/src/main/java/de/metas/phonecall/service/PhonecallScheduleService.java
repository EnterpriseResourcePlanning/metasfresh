package de.metas.phonecall.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.google.common.collect.ImmutableSet;

import de.metas.calendar.ICalendarBL;
import de.metas.phonecall.PhonecallSchedule;
import de.metas.phonecall.PhonecallSchema;
import de.metas.phonecall.PhonecallSchemaVersion;
import de.metas.phonecall.PhonecallSchemaVersionLine;
import de.metas.util.Check;
import de.metas.util.Services;
import de.metas.util.calendar.IBusinessDayMatcher;
import de.metas.util.time.generator.BusinessDayShifter;
import de.metas.util.time.generator.BusinessDayShifter.OnNonBussinessDay;
import de.metas.util.time.generator.DateSequenceGenerator;
import de.metas.util.time.generator.Frequency;
import de.metas.util.time.generator.IDateShifter;
import lombok.NonNull;

/*
 * #%L
 * de.metas.adempiere.adempiere.base
 * %%
 * Copyright (C) 2019 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

@Service
public class PhonecallScheduleService
{
	private final PhonecallScheduleRepository schedulesRepo;
	private final PhonecallSchemaRepository schemaRepo;

	public PhonecallScheduleService(
			@NonNull final PhonecallScheduleRepository schedulesRepo,
			@NonNull final PhonecallSchemaRepository schemaRepo)
	{
		this.schedulesRepo = schedulesRepo;
		this.schemaRepo = schemaRepo;
	}

	public void generatePhonecallSchedulesForSchema(final PhonecallSchema phonecallSchema, final LocalDate startDate, final LocalDate endDate)
	{
		final List<PhonecallSchemaVersionRange> phonecallSchemaVersionRanges = retrievePhonecallSchemaVersionRanges(phonecallSchema, startDate, endDate);

		for (final PhonecallSchemaVersionRange phonecallSchemaVersionRange : phonecallSchemaVersionRanges)
		{

			schemaRepo.inactivatePhonecallDaysInRange(phonecallSchemaVersionRange);

			final PhonecallSchemaVersion phonecallSchemaVersion = phonecallSchemaVersionRange.getPhonecallSchemaVersion();
			final List<PhonecallSchemaVersionLine> phonecallSchemaVersionLines = phonecallSchemaVersion.getLines();
			if (phonecallSchemaVersionLines.isEmpty())
			{
				continue;
			}
			for (final PhonecallSchemaVersionLine phonecallSchemaVersionLine : phonecallSchemaVersionLines)
			{
				createPhonecallSchedulesForLine(phonecallSchemaVersionRange, phonecallSchemaVersionLine);
			}
		}
	}

	public void createPhonecallSchedulesForLine(final PhonecallSchemaVersionRange phonecallSchemaVersionRange, final PhonecallSchemaVersionLine phonecallSchemaVersionLine)
	{
		final Set<LocalDate> phonecallDates = phonecallSchemaVersionRange.generatePhonecallDates();
		if (phonecallDates.isEmpty())
		{

			return;
		}

		for (final LocalDate currentPhonecallDate : phonecallDates)
		{
			createPhonecallSchedule(phonecallSchemaVersionLine, currentPhonecallDate);
		}

	}

	private void createPhonecallSchedule(PhonecallSchemaVersionLine phonecallSchemaVersionLine, LocalDate currentPhonecallDate)
	{
		final PhonecallSchedule phonecallSchedule = PhonecallSchedule.builder()
				.bpartnerAndLocationId(phonecallSchemaVersionLine.getBpartnerAndLocationId())
				.contactId(phonecallSchemaVersionLine.getContactId())
				.schemaVersionLineId(phonecallSchemaVersionLine.getId())
				.date(currentPhonecallDate)
				.startTime(phonecallSchemaVersionLine.getStartTime())
				.endTime(phonecallSchemaVersionLine.getEndTime())
				.build();

		schedulesRepo.save(phonecallSchedule);
	}

	/**
	 * Issue https://github.com/metasfresh/metasfresh/issues/4951
	 * I made this method similar with de.metas.tourplanning.api.impl.TourDAO.retrieveTourVersionRanges(I_M_Tour, LocalDate, LocalDate) and I kept the comments
	 * Maybe in the future these shall be somehow brought together in a single structure so we don't have similar code in 2 places.
	 *
	 * @param phonecallSchema
	 * @param dateFrom
	 * @param dateTo
	 * @return
	 */
	public List<PhonecallSchemaVersionRange> retrievePhonecallSchemaVersionRanges(
			@NonNull final PhonecallSchema phonecallSchema,
			@NonNull final LocalDate dateFrom,
			@NonNull final LocalDate dateTo)
	{
		Check.assume(dateFrom.compareTo(dateTo) <= 0, "startDate({}) <= endDate({})", dateFrom, dateTo);

		final List<PhonecallSchemaVersion> phonecallSchemaVersions = phonecallSchema.getChronologicallyOrderedPhonecallSchemaVersions(dateTo);
		if (phonecallSchemaVersions.isEmpty())
		{
			return Collections.emptyList();
		}

		//
		// Continue iterating the phonecall schema versions and create phonecall schema version ranges
		List<PhonecallSchemaVersionRange> phonecallSchemaVersionRanges = new ArrayList<>();
		boolean previousPhonecallVersionValid = false;
		PhonecallSchemaVersion previousPhonecallSchemaVersion = null;
		LocalDate previousPhonecallSchemaVersionValidFrom = null;

		final Iterator<PhonecallSchemaVersion> phonecallSchemaVersionsIterator = phonecallSchemaVersions.iterator();
		while (phonecallSchemaVersionsIterator.hasNext())
		{
			final PhonecallSchemaVersion phonecallSchemaVersion = phonecallSchemaVersionsIterator.next();

			final LocalDate phonecallSchemaVersionValidFrom = phonecallSchemaVersion.getValidFrom();

			Check.assumeNotNull(phonecallSchemaVersionValidFrom, "phonecallSchemaVersionValidFrom not null");

			//
			// Guard: phonecall schema version's ValidFrom shall be before "dateTo"
			if (phonecallSchemaVersionValidFrom.compareTo(dateTo) > 0)
			{
				// shall not happen because we retrieved until dateTo, but just to make sure
				break;
			}

			//
			// Case: We are still searching for first phonecall schema version to consider
			if (!previousPhonecallVersionValid)
			{
				// Case: our current phonecall schema version is before given dateFrom
				if (phonecallSchemaVersionValidFrom.compareTo(dateFrom) < 0)
				{
					if (!phonecallSchemaVersionsIterator.hasNext())
					{
						// there is no other next, so we need to consider this one
						previousPhonecallSchemaVersion = phonecallSchemaVersion;
						previousPhonecallSchemaVersionValidFrom = dateFrom;
						previousPhonecallVersionValid = true;
						continue;
					}
				}
				// Case: our current phonecall schema version starts exactly on our given dateFrom
				else if (phonecallSchemaVersionValidFrom.compareTo(dateFrom) == 0)
				{
					previousPhonecallSchemaVersion = phonecallSchemaVersion;
					previousPhonecallSchemaVersionValidFrom = dateFrom;
					previousPhonecallVersionValid = true;
					continue;
				}
				// Case: our current phonecall schema version start after our given dateFrom
				else
				{
					// Check if we have a previous phonecall schema version, because if we have, that shall be the first phonecall schema version to consider
					if (previousPhonecallSchemaVersion != null)
					{
						// NOTE: we consider dateFrom as first date because phonecall schema version's ValidFrom is before dateFrom
						previousPhonecallSchemaVersionValidFrom = dateFrom;
						previousPhonecallVersionValid = true;
						// don't continue: we got it right now
						// continue;
					}
					// ... else it seems this is the first phonecall schema version which actually starts after our dateFrom
					else
					{
						previousPhonecallSchemaVersion = phonecallSchemaVersion;
						previousPhonecallSchemaVersionValidFrom = phonecallSchemaVersionValidFrom;
						previousPhonecallVersionValid = true;
						continue;
					}
				}
			}

			//
			// Case: we do have a previous valid phonecall schema version to consider so we can generate phonecall schema ranges
			if (previousPhonecallVersionValid)
			{
				final LocalDate previousPhonecallSchemaVersionValidTo = phonecallSchemaVersionValidFrom.minusDays(1);
				final PhonecallSchemaVersionRange previousphonecallSchemaVersionRange = createPhonecallSchemaVersionRange(previousPhonecallSchemaVersion, previousPhonecallSchemaVersionValidFrom, previousPhonecallSchemaVersionValidTo);
				phonecallSchemaVersionRanges.add(previousphonecallSchemaVersionRange);
			}

			//
			// Set current as previous and move on
			previousPhonecallSchemaVersion = phonecallSchemaVersion;
			previousPhonecallSchemaVersionValidFrom = phonecallSchemaVersionValidFrom;
		}

		//
		// Create phonecall schema Version Range for last version
		if (previousPhonecallVersionValid)
		{
			final PhonecallSchemaVersionRange lastPhonecallSchemaVersionRange = createPhonecallSchemaVersionRange(previousPhonecallSchemaVersion, previousPhonecallSchemaVersionValidFrom, dateTo);
			phonecallSchemaVersionRanges.add(lastPhonecallSchemaVersionRange);
		}

		return phonecallSchemaVersionRanges;
	}

	private PhonecallSchemaVersionRange createPhonecallSchemaVersionRange(PhonecallSchemaVersion phonecallSchemaVersion, LocalDate startDate, LocalDate endDate)
	{
		return PhonecallSchemaVersionRange.builder()
				.phonecallSchemaVersion(phonecallSchemaVersion)
				.startDate(startDate)
				.endDate(endDate)
				.dateSequenceGenerator(createDateSequenceGenerator(phonecallSchemaVersion, startDate, endDate))
				.build();
	}

	public DateSequenceGenerator createDateSequenceGenerator(
			@NonNull final PhonecallSchemaVersion phonecallSchemaVersion,
			@NonNull final LocalDate validFrom,
			@NonNull final LocalDate validTo)
	{
		final Frequency frequency = schemaRepo.extractFrequency(phonecallSchemaVersion);
		if (frequency == null)
		{
			return null;
		}

		final OnNonBussinessDay onNonBusinessDay = schemaRepo.extractOnNonBussinessDayOrNull(phonecallSchemaVersion);

		return DateSequenceGenerator.builder()
				.dateFrom(validFrom)
				.dateTo(validTo)
				.shifter(createDateShifter(frequency, onNonBusinessDay))
				.frequency(frequency)
				.build();
	}

	private static IDateShifter createDateShifter(final Frequency frequency, final OnNonBussinessDay onNonBusinessDay)
	{
		final IBusinessDayMatcher businessDayMatcher = createBusinessDayMatcher(frequency, onNonBusinessDay);

		return BusinessDayShifter.builder()
				.businessDayMatcher(businessDayMatcher)
				.onNonBussinessDay(onNonBusinessDay != null ? onNonBusinessDay : OnNonBussinessDay.Cancel)
				.build();
	}

	public static IBusinessDayMatcher createBusinessDayMatcher(final Frequency frequency, final OnNonBussinessDay onNonBusinessDay)
	{
		final ICalendarBL calendarBL = Services.get(ICalendarBL.class);

		//
		// If user explicitly asked for a set of week days, don't consider them non-business days by default
		if (frequency.isWeekly()
				&& frequency.isOnlySomeDaysOfTheWeek()
				&& onNonBusinessDay == null)
		{
			return calendarBL.createBusinessDayMatcherExcluding(frequency.getOnlyDaysOfWeek());
		}
		else
		{
			return calendarBL.createBusinessDayMatcherExcluding(ImmutableSet.of());
		}
	}
}
