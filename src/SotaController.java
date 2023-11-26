import jp.vstone.RobotLib.*;
import java.awt.Color;

class SotaController {

    CRobotPose pose;
    CSotaMotion motion;

    public SotaController(State status) {
		CRobotMem mem = new CRobotMem();
        
		this.motion = new CSotaMotion(mem);
		this.pose = new CRobotPose();

        if(mem.Connect()) { //TODO throw error if this fails
            this.motion.InitRobot_Sota();
            updateStatus(status);
        }
    }

    public void updateStatus(State status) {

        if(status == State.STARTUP) {
            pose.setLED_Sota(Color.GRAY, Color.GRAY, 0, Color.GRAY);
        } else if(status == State.TALKING) {
            pose.setLED_Sota(Color.GREEN, Color.GREEN, 0, Color.GREEN);
        } else if (status == State.PAUSED) {
            pose.setLED_Sota(Color.YELLOW, Color.YELLOW, 0, Color.YELLOW);
        } else if (status == State.STOPPED) {
            pose.setLED_Sota(Color.RED, Color.RED, 0, Color.RED);
        }
        motion.play(pose, 250);
    }


}