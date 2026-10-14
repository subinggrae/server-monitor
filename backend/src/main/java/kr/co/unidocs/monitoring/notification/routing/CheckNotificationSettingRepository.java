package kr.co.unidocs.monitoring.notification.routing;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckNotificationSettingRepository extends JpaRepository<CheckNotificationSetting, Long> {

	/** 설정을 저장한 적 없는 Check는 기본값입니다. 알림 설정 API와 알림 전송이 같은 값을 쓰도록 여기서만 정합니다. */
	default int findRepeatIntervalMinutes(Long checkId) {
		return findById(checkId).map(CheckNotificationSetting::getRepeatIntervalMinutes)
				.orElse(CheckNotificationSetting.DEFAULT_REPEAT_INTERVAL_MINUTES);
	}

}
