package dji.sampleV5.aircraft.utils.wpml;

import java.util.List;

import dji.sdk.wpmz.value.mission.WaylineActionInfo;
import dji.sdk.wpmz.value.mission.WaylineWaypoint;

/**
 * @author feel.feng
 * @time 2023/07/05 5:31 下午
 * @description: 航點資訊模型，封裝 WaylineWaypoint 與其動作列表（WaylineActionInfo）
 */
public class WaypointInfoModel {

    WaylineWaypoint waylineWaypoint;
    List<WaylineActionInfo> actionInfos;

    public WaylineWaypoint getWaylineWaypoint() {
        return waylineWaypoint;
    }

    public void setWaylineWaypoint(WaylineWaypoint waylineWaypoint) {
        this.waylineWaypoint = waylineWaypoint;
    }

    public List<WaylineActionInfo> getActionInfos() {
        return actionInfos;
    }

    public void setActionInfos(List<WaylineActionInfo> actionInfos) {
        this.actionInfos = actionInfos;
    }

}
