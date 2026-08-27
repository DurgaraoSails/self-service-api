package com.sails.ai.selfserviceapi.activity.repository;

import java.time.LocalDate;

public interface DailyUsageProjection {

    LocalDate getDay();

    Long getTotalSeconds();
}
