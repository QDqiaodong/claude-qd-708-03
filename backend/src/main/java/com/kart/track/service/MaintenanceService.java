package com.kart.track.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kart.track.common.BizException;
import com.kart.track.domain.Kart;
import com.kart.track.domain.Maintenance;
import com.kart.track.domain.TrackDict;
import com.kart.track.dto.MaintenanceDtos;
import com.kart.track.dto.Views;
import com.kart.track.repository.KartRepository;
import com.kart.track.repository.MaintenanceRepository;

/** 模块五：维修保养（待修 → 维修中 → 已完工，总费用 = 工时 × 工时费 + 配件费）。 */
@Service
public class MaintenanceService {

    private static final String WAITING = "WAITING";
    private static final String REPAIRING = "REPAIRING";
    private static final String DONE = "DONE";
    private static final String AVAILABLE = "AVAILABLE";
    private static final String MAINTENANCE = "MAINTENANCE";
    private static final String ON_TRACK = "ON_TRACK";

    private final MaintenanceRepository maintenanceRepository;
    private final KartRepository kartRepository;

    public MaintenanceService(MaintenanceRepository maintenanceRepository, KartRepository kartRepository) {
        this.maintenanceRepository = maintenanceRepository;
        this.kartRepository = kartRepository;
    }

    /** 维修操作结果：消息需要根据同车剩余待修/维修中工单动态生成。 */
    public record MaintenanceActionResult(Views.MaintenanceView view, String message) {
    }

    @Transactional(readOnly = true)
    public List<Views.MaintenanceView> list() {
        Map<Long, String> kartNo = new HashMap<>();
        for (Kart kart : kartRepository.findAll()) {
            kartNo.put(kart.getId(), kart.getKartNo());
        }
        List<Views.MaintenanceView> list = new ArrayList<>();
        for (Maintenance item : maintenanceRepository.findAllByOrderByStartTimeDesc()) {
            list.add(toView(item, kartNo.get(item.getKartId())));
        }
        return list;
    }

    /** 费用统计：已完工单的配件费 / 工时费 / 总费用 */
    @Transactional(readOnly = true)
    public Views.MaintSummary summary() {
        List<Maintenance> all = maintenanceRepository.findAll();
        BigDecimal partsFee = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        long waiting = 0;
        long repairing = 0;
        long done = 0;
        for (Maintenance item : all) {
            if (WAITING.equals(item.getStatus())) {
                waiting += 1;
            } else if (REPAIRING.equals(item.getStatus())) {
                repairing += 1;
            } else if (DONE.equals(item.getStatus())) {
                done += 1;
                partsFee = partsFee.add(nz(item.getPartsFee()));
                totalFee = totalFee.add(nz(item.getTotalFee()));
            }
        }
        BigDecimal labor = totalFee.subtract(partsFee);
        return new Views.MaintSummary(all.size(), waiting, repairing, done, partsFee, labor, totalFee);
    }

    @Transactional
    public MaintenanceActionResult create(MaintenanceDtos.MaintSaveReq req) {
        if (!TrackDict.isValidMaintType(req.getMaintType())) {
            throw new BizException("维修类型不合法");
        }
        if (maintenanceRepository.existsByOrderNo(req.getOrderNo())) {
            throw new BizException("维修单号 " + req.getOrderNo() + " 已存在");
        }

        // 先锁车辆行：同车的开单/开工/完工串行执行，避免两个事务各自按旧车辆状态提交。
        Kart kart = kartRepository.findByIdForUpdate(req.getKartId())
                .orElseThrow(() -> new BizException("车辆不存在"));
        if (ON_TRACK.equals(kart.getStatus())) {
            throw new BizException(kart.getKartNo() + " 正在赛道上，不能开立维修单；请先收车或在红旗核查中送修");
        }
        long repairingCount = maintenanceRepository.countByKartIdAndStatus(kart.getId(), REPAIRING);
        long waitingCount = maintenanceRepository.countByKartIdAndStatus(kart.getId(), WAITING);

        BigDecimal hours = req.getWorkHours() == null ? BigDecimal.ONE : req.getWorkHours();
        BigDecimal parts = req.getPartsFee() == null ? BigDecimal.ZERO : req.getPartsFee();
        BigDecimal total = hours.multiply(TrackDict.LABOR_RATE).add(parts);

        LocalDateTime now = LocalDateTime.now();
        Maintenance item = new Maintenance();
        item.setOrderNo(req.getOrderNo());
        item.setKartId(kart.getId());
        item.setMaintType(req.getMaintType());
        item.setFaultDesc(req.getFaultDesc());
        item.setWorkHours(hours);
        item.setPartsFee(parts);
        item.setTotalFee(total);
        item.setWorker(req.getWorker());
        item.setStatus(WAITING);
        item.setStartTime(now);
        item.setEndTime(null);
        Maintenance saved;
        try {
            saved = maintenanceRepository.saveAndFlush(item);
        } catch (DataIntegrityViolationException e) {
            if (maintenanceRepository.existsByOrderNo(req.getOrderNo())) {
                throw new BizException("维修单号 " + req.getOrderNo() + " 已存在，请勿重复提交");
            }
            throw new BizException("维修单保存失败，请检查数据后重试");
        }

        String message;
        if (repairingCount > 0 || waitingCount > 0) {
            // 只要该车存在未结工单，新增待修单也必须继续锁住车辆，不能给后续派车留下可用状态。
            ensureMaintenance(kart, now);
            long waitingAfter = waitingCount + 1;
            message = "维修单已开立；" + kart.getKartNo() + " 现有 " + (repairingCount + waitingAfter)
                    + " 张未结工单（维修中 " + repairingCount + "、待修 " + waitingAfter
                    + "），车辆继续保持维修";
        } else {
            ensureMaintenance(kart, now);
            message = "维修单已开立；" + kart.getKartNo() + " 已转入维修，待全部未结工单完工后才会恢复可用";
        }
        kartRepository.save(kart);
        return new MaintenanceActionResult(toView(saved, kart.getKartNo()), message);
    }

    /** 开工：待修 → 维修中，车辆置为维修；同车其余未结工单不影响本单状态。 */
    @Transactional
    public MaintenanceActionResult start(Long id, MaintenanceDtos.WorkerReq req) {
        Maintenance item = maintenanceRepository.findById(id).orElseThrow(() -> new BizException("维修单不存在"));
        Long kartId = item.getKartId();

        // 按“车辆行 → 工单行”固定顺序加锁，确保同车并发开工/完工串行且不会死锁。
        Kart kart = kartRepository.findByIdForUpdate(kartId)
                .orElseThrow(() -> new BizException("车辆不存在"));
        item = maintenanceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("维修单不存在"));
        if (!WAITING.equals(item.getStatus())) {
            throw new BizException("只有「待修」的维修单可以开工，当前为"
                    + TrackDict.maintStatusName(item.getStatus()));
        }
        if (ON_TRACK.equals(kart.getStatus())) {
            throw new BizException(kart.getKartNo() + " 正在赛道上，不能开工维修");
        }
        String worker = item.getWorker();
        if (req != null && req.getWorker() != null && !req.getWorker().isBlank()) {
            worker = req.getWorker().trim();
        }
        if (worker == null || worker.isBlank()) {
            throw new BizException("请填写维修人");
        }

        long otherRepairing = maintenanceRepository.countByKartIdAndStatus(kart.getId(), REPAIRING);
        long otherWaiting = maintenanceRepository.countByKartIdAndStatusAndIdNot(kart.getId(), WAITING, id);

        item.setWorker(worker);
        item.setStatus(REPAIRING);
        item.setStartTime(LocalDateTime.now());
        maintenanceRepository.save(item);

        ensureMaintenance(kart, LocalDateTime.now());
        kartRepository.save(kart);

        String message;
        long otherOpen = otherRepairing + otherWaiting;
        if (otherOpen > 0) {
            message = "已开工；" + kart.getKartNo() + " 还有 " + otherOpen + " 张未结工单（维修中 "
                    + otherRepairing + "、待修 " + otherWaiting + "），车辆保持维修";
        } else {
            message = "已开工，" + kart.getKartNo() + " 已转入维修";
        }
        return new MaintenanceActionResult(toView(item, kart.getKartNo()), message);
    }

    /** 完工：维修中 → 已完工；只有同车不存在其他未结工单时，车辆才可恢复可用。 */
    @Transactional
    public MaintenanceActionResult complete(Long id) {
        Maintenance item = maintenanceRepository.findById(id).orElseThrow(() -> new BizException("维修单不存在"));
        Long kartId = item.getKartId();

        // 与开工/开单使用相同的加锁顺序，保证“最后一张工单完工”不会与另一张开工作业交叉。
        Kart kart = kartRepository.findByIdForUpdate(kartId)
                .orElseThrow(() -> new BizException("车辆不存在"));
        item = maintenanceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("维修单不存在"));
        if (!REPAIRING.equals(item.getStatus())) {
            throw new BizException("只有「维修中」的维修单可以完工，当前为"
                    + TrackDict.maintStatusName(item.getStatus()));
        }

        LocalDateTime now = LocalDateTime.now();
        item.setStatus(DONE);
        item.setEndTime(now);
        maintenanceRepository.saveAndFlush(item);

        // 已完工历史不参与判断；待修和维修中都属于未结维修，任一存在都不能放车。
        long repairingLeft = maintenanceRepository.countByKartIdAndStatusAndIdNot(kart.getId(), REPAIRING, id);
        long waitingLeft = maintenanceRepository.countByKartIdAndStatusAndIdNot(kart.getId(), WAITING, id);
        long openLeft = repairingLeft + waitingLeft;

        String message;
        if (openLeft > 0) {
            ensureMaintenance(kart, now);
            message = "工单已完工；" + kart.getKartNo() + " 仍有 " + openLeft + " 张未结工单（维修中 "
                    + repairingLeft + "、待修 " + waitingLeft + "），车辆继续保持维修";
        } else {
            kart.setStatus(AVAILABLE);
            kart.setUpdatedAt(now);
            message = "工单已完工；" + kart.getKartNo() + " 已无其他未结维修，车辆恢复可用";
        }
        if (TrackDict.isServiceType(item.getMaintType())) {
            kart.setLastServiceDate(LocalDate.now());
        }
        kartRepository.save(kart);
        return new MaintenanceActionResult(toView(item, kart.getKartNo()), message);
    }

    private void ensureMaintenance(Kart kart, LocalDateTime now) {
        if (!MAINTENANCE.equals(kart.getStatus())) {
            kart.setStatus(MAINTENANCE);
        }
        kart.setUpdatedAt(now);
    }

    Views.MaintenanceView toView(Maintenance item, String kartNo) {
        long minutes = 0;
        if (item.getStartTime() != null && item.getEndTime() != null) {
            minutes = ChronoUnit.MINUTES.between(item.getStartTime(), item.getEndTime());
        }
        return new Views.MaintenanceView(
                item.getId(),
                item.getOrderNo(),
                item.getKartId(),
                kartNo,
                item.getMaintType(),
                TrackDict.maintTypeName(item.getMaintType()),
                item.getFaultDesc(),
                item.getWorkHours(),
                item.getPartsFee(),
                item.getTotalFee(),
                item.getWorker(),
                item.getStartTime(),
                item.getEndTime(),
                item.getStatus(),
                TrackDict.maintStatusName(item.getStatus()),
                minutes);
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
