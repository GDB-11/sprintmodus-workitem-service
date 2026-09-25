package com.sprintmodus.workitem_service.domain.service;

import java.math.BigDecimal;

import com.sprintmodus.common_lib.result.Result;
import com.sprintmodus.common_lib.result.Unit;

/** Limits on a work item's effort, so a typo cannot skew a sprint's numbers. */
public final class EffortPointsService {

	public static final int MAX_POINTS = 1000;

	public static final BigDecimal MAX_HOURS = new BigDecimal("10000");

	private EffortPointsService() {
	}

	/** The error is a message safe to show, and names the field. */
	public record Invalid(String field, String message) {
	}

	public static Result<Unit, Invalid> validateEffort(int effortPoints, BigDecimal estimatedHours, BigDecimal remainingHours) {
		if (effortPoints < 0 || effortPoints > MAX_POINTS) {
			return Result.failure(new Invalid("effortPoints", "Effort points must be between 0 and " + MAX_POINTS + "."));
		}
		if (outOfRange(estimatedHours)) {
			return Result.failure(new Invalid("estimatedHours", "Estimated hours must be between 0 and " + MAX_HOURS + "."));
		}
		if (outOfRange(remainingHours)) {
			return Result.failure(new Invalid("remainingHours", "Remaining hours must be between 0 and " + MAX_HOURS + "."));
		}
		return Result.success(Unit.VALUE);
	}

	private static boolean outOfRange(BigDecimal hours) {
		return hours.signum() < 0 || hours.compareTo(MAX_HOURS) > 0 || hours.scale() > 2 && hours.stripTrailingZeros().scale() > 2;
	}

}
