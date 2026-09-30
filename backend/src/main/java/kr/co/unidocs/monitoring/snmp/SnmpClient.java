package kr.co.unidocs.monitoring.snmp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.util.DefaultPDUFactory;
import org.snmp4j.util.TableEvent;
import org.snmp4j.util.TableUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Resource Discovery와 연결 테스트에서 장비에 직접 SNMP로 묻습니다.
 *
 * snmp_exporter를 Discovery API처럼 쓰지 않습니다.
 * exporter의 역할은 Prometheus가 주기적으로 가져갈 metric을 내주는 것뿐입니다.
 *
 * 실패해도 예외를 밖으로 던지지 않습니다. 사용자에게는 "응답이 없다"까지만 알려 줍니다.
 */
@Component
public class SnmpClient {

	private static final Logger log = LoggerFactory.getLogger(SnmpClient.class);

	private static final int TIMEOUT_MILLIS = 3_000;

	private static final int RETRIES = 1;

	/** 한 장비에서 Storage 행이 이보다 많으면 화면에서 고를 수 있는 수준을 넘습니다. */
	private static final int MAX_STORAGE_ROWS = 200;

	public SnmpSystemInfo probe(SnmpTarget target) {
		long startedAt = System.currentTimeMillis();

		try (Snmp snmp = openSession()) {
			SnmpSystemInfo info = readSystemInfo(snmp, target, startedAt);
			if (!info.reachable()) {
				log.info("SNMP 응답을 받지 못했습니다. host={} port={}", target.host(), target.port());
			}
			return info;
		}
		catch (IOException ex) {
			log.warn("SNMP 연결을 열지 못했습니다. host={} port={} reason={}", target.host(), target.port(),
					ex.getClass().getSimpleName());
			return SnmpSystemInfo.unreachable();
		}
	}

	public SnmpWalkResult discover(SnmpTarget target) {
		long startedAt = System.currentTimeMillis();

		try (Snmp snmp = openSession()) {
			SnmpSystemInfo systemInfo = readSystemInfo(snmp, target, startedAt);
			if (!systemInfo.reachable()) {
				log.info("SNMP 응답을 받지 못해 Resource를 검색하지 못했습니다. host={} port={}", target.host(), target.port());
				return SnmpWalkResult.unreachable();
			}

			int processorCount = countProcessors(snmp, target);
			List<SnmpStorageEntry> storageEntries = readStorageTable(snmp, target);

			log.info("SNMP Resource를 검색했습니다. host={} processors={} storageRows={}", target.host(), processorCount,
					storageEntries.size());
			return new SnmpWalkResult(true, systemInfo, processorCount, storageEntries);
		}
		catch (IOException ex) {
			log.warn("SNMP 연결을 열지 못했습니다. host={} port={} reason={}", target.host(), target.port(),
					ex.getClass().getSimpleName());
			return SnmpWalkResult.unreachable();
		}
	}

	private Snmp openSession() throws IOException {
		Snmp snmp = new Snmp(new DefaultUdpTransportMapping());
		snmp.listen();
		return snmp;
	}

	private SnmpSystemInfo readSystemInfo(Snmp snmp, SnmpTarget target, long startedAt) {
		PDU request = new PDU();
		request.setType(PDU.GET);
		request.add(new VariableBinding(new OID(SnmpOids.SYS_NAME)));
		request.add(new VariableBinding(new OID(SnmpOids.SYS_DESCR)));
		request.add(new VariableBinding(new OID(SnmpOids.SYS_UPTIME)));

		try {
			PDU response = snmp.send(request, communityTarget(target)).getResponse();
			if (response == null || response.getErrorStatus() != PDU.noError) {
				return SnmpSystemInfo.unreachable();
			}

			String systemName = stringValue(response, SnmpOids.SYS_NAME);
			String systemDescription = stringValue(response, SnmpOids.SYS_DESCR);
			Long uptimeSeconds = uptimeSeconds(response);

			if (systemName == null && systemDescription == null) {
				return SnmpSystemInfo.unreachable();
			}

			long elapsed = System.currentTimeMillis() - startedAt;
			return new SnmpSystemInfo(true, systemName, systemDescription, uptimeSeconds, elapsed);
		}
		catch (IOException ex) {
			return SnmpSystemInfo.unreachable();
		}
	}

	/** 사용률은 Prometheus가 수집합니다. 여기에서는 processor가 몇 개 있는지만 확인합니다. */
	private int countProcessors(Snmp snmp, SnmpTarget target) {
		return walkColumns(snmp, target, SnmpOids.HR_PROCESSOR_LOAD).size();
	}

	private List<SnmpStorageEntry> readStorageTable(Snmp snmp, SnmpTarget target) {
		List<TableEvent> rows = walkColumns(snmp, target, SnmpOids.HR_STORAGE_TYPE, SnmpOids.HR_STORAGE_DESCR,
				SnmpOids.HR_STORAGE_ALLOCATION_UNITS, SnmpOids.HR_STORAGE_SIZE, SnmpOids.HR_STORAGE_USED);

		List<SnmpStorageEntry> entries = new ArrayList<>();
		for (TableEvent row : rows) {
			if (entries.size() >= MAX_STORAGE_ROWS) {
				break;
			}
			SnmpStorageEntry entry = toStorageEntry(row);
			if (entry != null) {
				entries.add(entry);
			}
		}
		return List.copyOf(entries);
	}

	private SnmpStorageEntry toStorageEntry(TableEvent row) {
		VariableBinding[] columns = row.getColumns();
		if (columns == null || columns.length < 5) {
			return null;
		}

		String type = (columns[0] != null) ? columns[0].getVariable().toString() : null;
		String description = (columns[1] != null) ? columns[1].getVariable().toString() : null;
		if (type == null || description == null || description.isBlank()) {
			return null;
		}

		long allocationUnits = longValue(columns[2]);
		long size = longValue(columns[3]);
		long used = longValue(columns[4]);
		if (allocationUnits <= 0 || size <= 0) {
			// 크기를 알 수 없는 행은 사용률을 계산할 수 없어 고를 수 있게 두지 않습니다.
			return null;
		}

		int index = row.getIndex().last();
		return new SnmpStorageEntry(index, type, description.trim(), allocationUnits, size, used);
	}

	private List<TableEvent> walkColumns(Snmp snmp, SnmpTarget target, String... columnOids) {
		OID[] columns = new OID[columnOids.length];
		for (int i = 0; i < columnOids.length; i++) {
			columns[i] = new OID(columnOids[i]);
		}

		TableUtils tableUtils = new TableUtils(snmp, new DefaultPDUFactory(PDU.GETBULK));
		List<TableEvent> events = tableUtils.getTable(communityTarget(target), columns, null, null);

		List<TableEvent> rows = new ArrayList<>();
		for (TableEvent event : events) {
			if (event == null || event.isError() || event.getIndex() == null) {
				continue;
			}
			rows.add(event);
		}
		return rows;
	}

	private CommunityTarget<UdpAddress> communityTarget(SnmpTarget target) {
		CommunityTarget<UdpAddress> communityTarget = new CommunityTarget<>();
		communityTarget.setCommunity(new OctetString(target.community()));
		communityTarget.setAddress(new UdpAddress(target.host() + "/" + target.port()));
		communityTarget.setVersion(SnmpConstants.version2c);
		communityTarget.setTimeout(TIMEOUT_MILLIS);
		communityTarget.setRetries(RETRIES);
		return communityTarget;
	}

	private String stringValue(PDU response, String oid) {
		Variable variable = response.getVariable(new OID(oid));
		if (variable == null || variable.isException()) {
			return null;
		}
		String value = variable.toString();
		return value.isBlank() ? null : value;
	}

	private Long uptimeSeconds(PDU response) {
		Variable variable = response.getVariable(new OID(SnmpOids.SYS_UPTIME));
		if (variable == null || variable.isException()) {
			return null;
		}
		// sysUpTime은 1/100초 단위입니다.
		return variable.toLong() / 100;
	}

	private long longValue(VariableBinding binding) {
		if (binding == null || binding.isException()) {
			return 0;
		}
		try {
			return binding.getVariable().toLong();
		}
		catch (UnsupportedOperationException ex) {
			return 0;
		}
	}

}
