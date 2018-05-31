package com.itranswarp.crypto.candiess.web.modules.admin.canal;

import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import com.alibaba.otter.canal.common.CanalException;
import com.alibaba.otter.canal.common.alarm.CanalAlarmHandler;
import com.alibaba.otter.canal.common.alarm.LogAlarmHandler;
import com.alibaba.otter.canal.common.utils.JsonUtils;
import com.alibaba.otter.canal.common.zookeeper.ZkClientx;
import com.alibaba.otter.canal.filter.aviater.AviaterRegexFilter;
import com.alibaba.otter.canal.instance.core.AbstractCanalInstance;
import com.alibaba.otter.canal.instance.manager.model.Canal;
import com.alibaba.otter.canal.instance.manager.model.CanalParameter;
import com.alibaba.otter.canal.instance.manager.model.CanalParameter.DataSourcing;
import com.alibaba.otter.canal.instance.manager.model.CanalParameter.HAMode;
import com.alibaba.otter.canal.instance.manager.model.CanalParameter.IndexMode;
import com.alibaba.otter.canal.instance.manager.model.CanalParameter.MetaMode;
import com.alibaba.otter.canal.instance.manager.model.CanalParameter.SourcingType;
import com.alibaba.otter.canal.instance.manager.model.CanalParameter.StorageMode;
import com.alibaba.otter.canal.instance.manager.model.CanalParameter.StorageScavengeMode;
import com.alibaba.otter.canal.meta.MemoryMetaManager;
import com.alibaba.otter.canal.meta.PeriodMixedMetaManager;
import com.alibaba.otter.canal.meta.ZooKeeperMetaManager;
import com.alibaba.otter.canal.parse.CanalEventParser;
import com.alibaba.otter.canal.parse.ha.CanalHAController;
import com.alibaba.otter.canal.parse.ha.HeartBeatHAController;
import com.alibaba.otter.canal.parse.inbound.AbstractEventParser;
import com.alibaba.otter.canal.parse.inbound.group.GroupEventParser;
import com.alibaba.otter.canal.parse.inbound.mysql.LocalBinlogEventParser;
import com.alibaba.otter.canal.parse.index.CanalLogPositionManager;
import com.alibaba.otter.canal.parse.index.FailbackLogPositionManager;
import com.alibaba.otter.canal.parse.index.MemoryLogPositionManager;
import com.alibaba.otter.canal.parse.index.MetaLogPositionManager;
import com.alibaba.otter.canal.parse.index.PeriodMixedLogPositionManager;
import com.alibaba.otter.canal.parse.index.ZooKeeperLogPositionManager;
import com.alibaba.otter.canal.parse.support.AuthenticationInfo;
import com.alibaba.otter.canal.protocol.position.EntryPosition;
import com.alibaba.otter.canal.sink.entry.EntryEventSink;
import com.alibaba.otter.canal.sink.entry.group.GroupEventSink;
import com.alibaba.otter.canal.store.AbstractCanalStoreScavenge;
import com.alibaba.otter.canal.store.memory.MemoryEventStoreWithBuffer;
import com.alibaba.otter.canal.store.model.BatchMode;

/**
 * 单个canal实例，比如一个destination会独立一个实例
 * 
 * @author jianghang 2012-7-11 下午09:26:51
 * @version 1.0.0
 */
public class ManageCanalInstanceWithManager extends AbstractCanalInstance {

	private static final Logger logger = LoggerFactory.getLogger(ManageCanalInstanceWithManager.class);
	protected String filter; // 过滤表达式
	protected CanalParameter parameters; // 对应参数
	protected long id;

	public ManageCanalInstanceWithManager(Canal canal, String filter, Long id) {
		this.id = id;
		this.parameters = canal.getCanalParameter();
		this.canalId = canal.getId();
		this.destination = canal.getName();
		this.filter = filter;

		logger.info("init CanalInstance for {}-{} with parameters:{}", canalId, destination, parameters);
		// 初始化报警机制
		initAlarmHandler();
		// 初始化metaManager
		initMetaManager();
		// 初始化eventStore
		initEventStore();
		// 初始化eventSink
		initEventSink();
		// 初始化eventParser;
		initEventParser();

		// 基础工具，需要提前start，会有先订阅再根据filter条件启动parse的需求
		if (!alarmHandler.isStart()) {
			alarmHandler.start();
		}

		if (!metaManager.isStart()) {
			metaManager.start();
		}
		logger.info("init successful....");
	}

	public void start() {
		// 初始化metaManager
		logger.info("start CannalInstance for {}-{} with parameters:{}", canalId, destination, parameters);
		super.start();
	}

	protected void initAlarmHandler() {
		logger.info("init alarmHandler begin...");
		alarmHandler = new LogAlarmHandler();
		logger.info("init alarmHandler end! \n\t load CanalAlarmHandler:{} ", alarmHandler.getClass().getName());
	}

	protected void initMetaManager() {
		logger.info("init metaManager begin...");
		MetaMode mode = parameters.getMetaMode();
		if (mode.isMemory()) {
			metaManager = new MemoryMetaManager();
		} else if (mode.isZookeeper()) {
			metaManager = new ZooKeeperMetaManager();
			((ZooKeeperMetaManager) metaManager).setZkClientx(getZkclientx());
		} else if (mode.isMixed()) {
			// metaManager = new MixedMetaManager();
			metaManager = new PeriodMixedMetaManager();// 换用优化过的mixed, at
														// 2012-09-11
			// 设置内嵌的zk metaManager
			ZooKeeperMetaManager zooKeeperMetaManager = new ZooKeeperMetaManager();
			zooKeeperMetaManager.setZkClientx(getZkclientx());
			((PeriodMixedMetaManager) metaManager).setZooKeeperMetaManager(zooKeeperMetaManager);
		} else {
			throw new CanalException("unsupport MetaMode for " + mode);
		}

		logger.info("init metaManager end! \n\t load CanalMetaManager:{} ", metaManager.getClass().getName());
	}

	protected void initEventStore() {
		logger.info("init eventStore begin...");
		StorageMode mode = parameters.getStorageMode();
		if (mode.isMemory()) {
			MemoryEventStoreWithBuffer memoryEventStore = new MemoryEventStoreWithBuffer();
			memoryEventStore.setBufferSize(parameters.getMemoryStorageBufferSize());
			memoryEventStore.setBufferMemUnit(parameters.getMemoryStorageBufferMemUnit());
			memoryEventStore.setBatchMode(BatchMode.valueOf(parameters.getStorageBatchMode().name()));
			memoryEventStore.setDdlIsolation(parameters.getDdlIsolation());
			eventStore = memoryEventStore;
		} else if (mode.isFile()) {
			// 后续版本支持
			throw new CanalException("unsupport MetaMode for " + mode);
		} else if (mode.isMixed()) {
			// 后续版本支持
			throw new CanalException("unsupport MetaMode for " + mode);
		} else {
			throw new CanalException("unsupport MetaMode for " + mode);
		}

		if (eventStore instanceof AbstractCanalStoreScavenge) {
			StorageScavengeMode scavengeMode = parameters.getStorageScavengeMode();
			AbstractCanalStoreScavenge eventScavengeStore = (AbstractCanalStoreScavenge) eventStore;
			eventScavengeStore.setDestination(destination);
			eventScavengeStore.setCanalMetaManager(metaManager);
			eventScavengeStore.setOnAck(scavengeMode.isOnAck());
			eventScavengeStore.setOnFull(scavengeMode.isOnFull());
			eventScavengeStore.setOnSchedule(scavengeMode.isOnSchedule());
			if (scavengeMode.isOnSchedule()) {
				eventScavengeStore.setScavengeSchedule(parameters.getScavengeSchdule());
			}
		}
		logger.info("init eventStore end! \n\t load CanalEventStore:{}", eventStore.getClass().getName());
	}

	protected void initEventSink() {
		logger.info("init eventSink begin...");

		int groupSize = getGroupSize();
		if (groupSize <= 1) {
			eventSink = new EntryEventSink();
		} else {
			eventSink = new GroupEventSink(groupSize);
		}

		if (eventSink instanceof EntryEventSink) {
			((EntryEventSink) eventSink).setFilterTransactionEntry(false);
			((EntryEventSink) eventSink).setEventStore(getEventStore());
		}
		// if (StringUtils.isNotEmpty(filter)) {
		// AviaterRegexFilter aviaterFilter = new AviaterRegexFilter(filter);
		// ((AbstractCanalEventSink) eventSink).setFilter(aviaterFilter);
		// }
		logger.info("init eventSink end! \n\t load CanalEventSink:{}", eventSink.getClass().getName());
	}

	protected void initEventParser() {
		logger.info("init eventParser begin...");
		SourcingType type = parameters.getSourcingType();

		List<List<DataSourcing>> groupDbAddresses = parameters.getGroupDbAddresses();
		if (!CollectionUtils.isEmpty(groupDbAddresses)) {
			int size = groupDbAddresses.get(0).size();// 取第一个分组的数量，主备分组的数量必须一致
			List<CanalEventParser> eventParsers = new ArrayList<CanalEventParser>();
			for (int i = 0; i < size; i++) {
				List<InetSocketAddress> dbAddress = new ArrayList<InetSocketAddress>();
				SourcingType lastType = null;
				for (List<DataSourcing> groupDbAddress : groupDbAddresses) {
					if (lastType != null && !lastType.equals(groupDbAddress.get(i).getType())) {
						throw new CanalException(String.format("master/slave Sourcing type is unmatch. %s vs %s",
								lastType, groupDbAddress.get(i).getType()));
					}

					lastType = groupDbAddress.get(i).getType();
					dbAddress.add(groupDbAddress.get(i).getDbAddress());
				}

				// 初始化其中的一个分组parser
				eventParsers.add(doInitEventParser(lastType, dbAddress));
			}

			if (eventParsers.size() > 1) { // 如果存在分组，构造分组的parser
				GroupEventParser groupEventParser = new GroupEventParser();
				groupEventParser.setEventParsers(eventParsers);
				this.eventParser = groupEventParser;
			} else {
				this.eventParser = eventParsers.get(0);
			}
		} else {
			// 创建一个空数据库地址的parser，可能使用了tddl指定地址，启动的时候才会从tddl获取地址
			this.eventParser = doInitEventParser(type, new ArrayList<InetSocketAddress>());
		}

		logger.info("init eventParser end! \n\t load CanalEventParser:{}", eventParser.getClass().getName());
	}

	private CanalEventParser doInitEventParser(SourcingType type, List<InetSocketAddress> dbAddresses) {
		CanalEventParser eventParser;
		if (type.isMysql()) {
			ManageMysqlEventParser mysqlEventParser = new ManageMysqlEventParser();
			mysqlEventParser.setId(id);
			mysqlEventParser.setDestination(destination);
			// 编码参数
			mysqlEventParser.setConnectionCharset(Charset.forName(parameters.getConnectionCharset()));
			mysqlEventParser.setConnectionCharsetNumber(parameters.getConnectionCharsetNumber());
			// 网络相关参数
			mysqlEventParser.setDefaultConnectionTimeoutInSeconds(parameters.getDefaultConnectionTimeoutInSeconds());
			mysqlEventParser.setSendBufferSize(parameters.getSendBufferSize());
			mysqlEventParser.setReceiveBufferSize(parameters.getReceiveBufferSize());
			// 心跳检查参数
			mysqlEventParser.setDetectingEnable(parameters.getDetectingEnable());
			mysqlEventParser.setDetectingSQL(parameters.getDetectingSQL());
			mysqlEventParser.setDetectingIntervalInSeconds(parameters.getDetectingIntervalInSeconds());
			// 数据库信息参数
			mysqlEventParser.setSlaveId(parameters.getSlaveId());
			if (!CollectionUtils.isEmpty(dbAddresses)) {
				mysqlEventParser.setMasterInfo(new AuthenticationInfo(dbAddresses.get(0), parameters.getDbUsername(),
						parameters.getDbPassword(), parameters.getDefaultDatabaseName()));

				if (dbAddresses.size() > 1) {
					mysqlEventParser
							.setStandbyInfo(new AuthenticationInfo(dbAddresses.get(1), parameters.getDbUsername(),
									parameters.getDbPassword(), parameters.getDefaultDatabaseName()));
				}
			}

			if (!CollectionUtils.isEmpty(parameters.getPositions())) {
				EntryPosition masterPosition = JsonUtils.unmarshalFromString(parameters.getPositions().get(0),
						EntryPosition.class);
				// binlog位置参数
				mysqlEventParser.setMasterPosition(masterPosition);

				if (parameters.getPositions().size() > 1) {
					EntryPosition standbyPosition = JsonUtils.unmarshalFromString(parameters.getPositions().get(0),
							EntryPosition.class);
					mysqlEventParser.setStandbyPosition(standbyPosition);
				}
			}
			mysqlEventParser.setFallbackIntervalInSeconds(parameters.getFallbackIntervalInSeconds());
			mysqlEventParser.setProfilingEnabled(false);
			mysqlEventParser.setFilterTableError(parameters.getFilterTableError());
			eventParser = mysqlEventParser;
		} else if (type.isLocalBinlog()) {
			LocalBinlogEventParser localBinlogEventParser = new LocalBinlogEventParser();
			localBinlogEventParser.setDestination(destination);
			localBinlogEventParser.setBufferSize(parameters.getReceiveBufferSize());
			localBinlogEventParser.setConnectionCharset(Charset.forName(parameters.getConnectionCharset()));
			localBinlogEventParser.setConnectionCharsetNumber(parameters.getConnectionCharsetNumber());
			localBinlogEventParser.setDirectory(parameters.getLocalBinlogDirectory());
			localBinlogEventParser.setProfilingEnabled(false);
			localBinlogEventParser.setDetectingEnable(parameters.getDetectingEnable());
			localBinlogEventParser.setDetectingIntervalInSeconds(parameters.getDetectingIntervalInSeconds());
			localBinlogEventParser.setFilterTableError(parameters.getFilterTableError());
			// 数据库信息，反查表结构时需要
			if (!CollectionUtils.isEmpty(dbAddresses)) {
				localBinlogEventParser.setMasterInfo(new AuthenticationInfo(dbAddresses.get(0),
						parameters.getDbUsername(), parameters.getDbPassword(), parameters.getDefaultDatabaseName()));

			}
			eventParser = localBinlogEventParser;
		} else if (type.isOracle()) {
			throw new CanalException("unsupport SourcingType for " + type);
		} else {
			throw new CanalException("unsupport SourcingType for " + type);
		}

		// add transaction support at 2012-12-06
		if (eventParser instanceof AbstractEventParser) {
			AbstractEventParser abstractEventParser = (AbstractEventParser) eventParser;
			abstractEventParser.setTransactionSize(parameters.getTransactionSize());
			abstractEventParser.setLogPositionManager(initLogPositionManager());
			abstractEventParser.setAlarmHandler(getAlarmHandler());
			abstractEventParser.setEventSink(getEventSink());

			if (StringUtils.isNotEmpty(filter)) {
				AviaterRegexFilter aviaterFilter = new AviaterRegexFilter(filter);
				abstractEventParser.setEventFilter(aviaterFilter);
			}

			// 设置黑名单
			if (StringUtils.isNotEmpty(parameters.getBlackFilter())) {
				AviaterRegexFilter aviaterFilter = new AviaterRegexFilter(parameters.getBlackFilter());
				abstractEventParser.setEventBlackFilter(aviaterFilter);
			}
		}
		if (eventParser instanceof ManageMysqlEventParser) {
			ManageMysqlEventParser mysqlEventParser = (ManageMysqlEventParser) eventParser;

			// 初始化haController，绑定与eventParser的关系，haController会控制eventParser
			CanalHAController haController = initHaController();
			mysqlEventParser.setHaController(haController);
		}
		return eventParser;
	}

	protected CanalHAController initHaController() {
		logger.info("init haController begin...");
		HAMode haMode = parameters.getHaMode();
		CanalHAController haController = null;
		if (haMode.isHeartBeat()) {
			haController = new HeartBeatHAController();
			((HeartBeatHAController) haController).setDetectingRetryTimes(parameters.getDetectingRetryTimes());
			((HeartBeatHAController) haController).setSwitchEnable(parameters.getHeartbeatHaEnable());
		} else {
			throw new CanalException("unsupport HAMode for " + haMode);
		}
		logger.info("init haController end! \n\t load CanalHAController:{}", haController.getClass().getName());

		return haController;
	}

	protected CanalLogPositionManager initLogPositionManager() {
		logger.info("init logPositionPersistManager begin...");
		IndexMode indexMode = parameters.getIndexMode();
		CanalLogPositionManager logPositionManager;
		if (indexMode.isMemory()) {
			logPositionManager = new MemoryLogPositionManager();
		} else if (indexMode.isZookeeper()) {
			logPositionManager = new ZooKeeperLogPositionManager(getZkclientx());
		} else if (indexMode.isMixed()) {
			MemoryLogPositionManager memoryLogPositionManager = new MemoryLogPositionManager();
			ZooKeeperLogPositionManager zooKeeperLogPositionManager = new ZooKeeperLogPositionManager(getZkclientx());
			logPositionManager = new PeriodMixedLogPositionManager(memoryLogPositionManager,
					zooKeeperLogPositionManager, 1000L);
		} else if (indexMode.isMeta()) {
			logPositionManager = new MetaLogPositionManager(metaManager);
		} else if (indexMode.isMemoryMetaFailback()) {
			MemoryLogPositionManager primary = new MemoryLogPositionManager();
			MetaLogPositionManager secondary = new MetaLogPositionManager(metaManager);

			logPositionManager = new FailbackLogPositionManager(primary, secondary);
		} else {
			throw new CanalException("unsupport indexMode for " + indexMode);
		}

		logger.info("init logPositionManager end! \n\t load CanalLogPositionManager:{}",
				logPositionManager.getClass().getName());

		return logPositionManager;
	}

	protected void startEventParserInternal(CanalEventParser eventParser, boolean isGroup) {
		if (eventParser instanceof AbstractEventParser) {
			AbstractEventParser abstractEventParser = (AbstractEventParser) eventParser;
			abstractEventParser.setAlarmHandler(getAlarmHandler());
		}

		super.startEventParserInternal(eventParser, isGroup);
	}

	private int getGroupSize() {
		List<List<DataSourcing>> groupDbAddresses = parameters.getGroupDbAddresses();
		if (!CollectionUtils.isEmpty(groupDbAddresses)) {
			return groupDbAddresses.get(0).size();
		} else {
			// 可能是基于tddl的启动
			return 1;
		}
	}

	private synchronized ZkClientx getZkclientx() {
		// 做一下排序，保证相同的机器只使用同一个链接
		List<String> zkClusters = new ArrayList<String>(parameters.getZkClusters());
		Collections.sort(zkClusters);

		return ZkClientx.getZkClient(StringUtils.join(zkClusters, ";"));
	}

	public void setAlarmHandler(CanalAlarmHandler alarmHandler) {
		this.alarmHandler = alarmHandler;
	}

}