package kr.co.unidocs.monitoring.notification.routing;

import kr.co.unidocs.monitoring.check.MonitoringCheckRepository;
import kr.co.unidocs.monitoring.notification.telegram.TelegramDestinationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Check 알림 설정의 재발송 주기")
class CheckNotificationServiceTest {

	private static final long CHECK_ID = 3L;

	private final CheckNotificationBindingRepository bindingRepository = mock(CheckNotificationBindingRepository.class);

	private final CheckNotificationSettingRepository settingRepository = mock(CheckNotificationSettingRepository.class);

	private final MonitoringCheckRepository checkRepository = mock(MonitoringCheckRepository.class);

	private final CheckNotificationService service = new CheckNotificationService(bindingRepository,
			settingRepository, mock(TelegramDestinationRepository.class), checkRepository);

	/** 저장한 설정을 다시 읽을 수 있게 Map으로 흉내 낸 저장소입니다. */
	private final Map<Long, CheckNotificationSetting> storedSettings = new HashMap<>();

	@BeforeEach
	void stubCollaborators() {
		when(checkRepository.existsById(CHECK_ID)).thenReturn(true);
		when(bindingRepository.findAllByCheckId(CHECK_ID)).thenReturn(List.of());
		when(settingRepository.findById(anyLong()))
				.then(invocation -> Optional.ofNullable(storedSettings.get(invocation.<Long>getArgument(0))));
		when(settingRepository.save(any(CheckNotificationSetting.class))).then(invocation -> {
			CheckNotificationSetting setting = invocation.getArgument(0);
			storedSettings.put(setting.getCheckId(), setting);
			return setting;
		});
		when(settingRepository.findRepeatIntervalMinutes(anyLong())).thenCallRealMethod();
	}

	@Test
	@DisplayName("재발송 주기를 저장한 적 없으면 60분으로 돌려준다")
	void returnsDefaultWithoutSetting() {
		assertThat(service.findByCheck(CHECK_ID).repeatIntervalMinutes()).isEqualTo(60);
	}

	@Test
	@DisplayName("요청의 재발송 주기를 저장하고 응답에 담는다")
	void savesRequestedInterval() {
		CheckNotificationResponse response = service.replace(CHECK_ID, request(new BigDecimal("15")));

		assertThat(response.repeatIntervalMinutes()).isEqualTo(15);
		assertThat(service.findByCheck(CHECK_ID).repeatIntervalMinutes()).isEqualTo(15);
	}

	@Test
	@DisplayName("저장한 재발송 주기를 다시 바꾼다")
	void changesStoredInterval() {
		service.replace(CHECK_ID, request(new BigDecimal("15")));

		service.replace(CHECK_ID, request(new BigDecimal("120")));

		assertThat(service.findByCheck(CHECK_ID).repeatIntervalMinutes()).isEqualTo(120);
	}

	@Test
	@DisplayName("재발송 주기 없이 저장하면 기존 주기를 그대로 둔다")
	void keepsIntervalWhenSavedWithout() {
		service.replace(CHECK_ID, request(new BigDecimal("15")));

		CheckNotificationResponse response = service.replace(CHECK_ID, request(null));

		assertThat(response.repeatIntervalMinutes()).isEqualTo(15);
	}

	private CheckNotificationRequest request(BigDecimal repeatIntervalMinutes) {
		return new CheckNotificationRequest(List.of(), repeatIntervalMinutes);
	}

}
