package frc.Mechanisms;

import com.ctre.phoenix.motorcontrol.ControlMode;
import com.ctre.phoenix.motorcontrol.NeutralMode;
import com.ctre.phoenix.motorcontrol.LimitSwitchNormal;
import com.ctre.phoenix.motorcontrol.LimitSwitchSource;
import com.ctre.phoenix.motorcontrol.SupplyCurrentLimitConfiguration;
import com.ctre.phoenix.motorcontrol.can.WPI_TalonFX;

import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.RobotBase;
import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj.drive.RobotDriveBase;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import frc.DataLogger.CatzLog;
import frc.DataLogger.DataCollection;
import frc.robot.*;
import frc.robot.Robot.mechMode;

public class CatzArm
{
    private WPI_TalonFX armMtr;

    private final int ARM_MC_ID = 20;

    private final double EXTEND_PWR  = 0.2;
    private final double RETRACT_PWR = -0.2;

    //Conversion factors

    //current limiting
    private SupplyCurrentLimitConfiguration armCurrentLimit;
    private final int     CURRENT_LIMIT_AMPS            = 55;
    private final int     CURRENT_LIMIT_TRIGGER_AMPS    = 55;
    private final double  CURRENT_LIMIT_TIMEOUT_SECONDS = 0.5;
    private final boolean ENABLE_CURRENT_LIMIT          = true;

    //gear ratio
    private final double VERSA_RATIO  = 7.0/1.0;

    private final double PUILEY_1      = 24.0;
    private final double PUILEY_2      = 18.0;
    private final double PUILEY_RATIO  = PUILEY_1 / PUILEY_2;
     
    private final double FINAL_RATIO   = VERSA_RATIO * PUILEY_RATIO;
    private final double FINAL_CIRCUMFERENCE = 3.54; 


    private final boolean LIMIT_SWITCH_IGNORED = false;
    private final boolean LIMIT_SWITCH_MONITORED = true;

    private final double CNTS_OVER_REV = 2048.0 / 1.0;

    private final double CNTS_PER_INCH_CONVERSION_FACTOR = CNTS_OVER_REV/FINAL_CIRCUMFERENCE;

    private final double POS_ENC_INCH_RETRACT = 0.0;
    private final double POS_ENC_INCH_EXTEND = 8.157;
    private final double POS_ENC_INCH_PICKUP = 4.157;

    private final double POS_ENC_CNTS_RETRACT  = 0.0;//POS_ENC_INCH_RETRACT * CNTS_PER_INCH_CONVERSION_FACTOR;
    private final double POS_ENC_CNTS_EXTEND  = 44000.0;//POS_ENC_INCH_EXTEND * CNTS_PER_INCH_CONVERSION_FACTOR;
    private final double POS_ENC_CNTS_PICKUP = 22000.0;//POS_ENC_INCH_PICKUP * CNTS_PER_INCH_CONVERSION_FACTOR;
    private final double POS_ENC_CNTS_HIGH_EXTEND_THRESHOLD_ELEVATOR = 73000.0;


    private boolean extendSwitchState = false;

    private int SWITCH_CLOSED = 1;

    private final double ARM_KP = 0.15;
    private final double ARM_KI = 0.0001;
    private final double ARM_KD = 0.0;

    private final double ARM_CLOSELOOP_ERROR = 3000;

    private final double MANUAL_CONTROL_PWR_OFF = 0.0;

    private boolean highExtendProcess = false;

    private int armMovementMode = Robot.MODE_AUTO;

    private double targetPosition = -999.0;
    private double currentPosition = -999.0;
    private double positionError = -999.0; 
    private double elevatorPosition = -999.0;

    private boolean armInPosition = false;
    private int numConsectSamples = 0;

    private final double ERROR_ARM_THRESHOLD = 500;
    private final double NO_TARGET_POSITION = -999999.0;

    CatzLog data;


    public CatzArm()
    {
        armMtr = new WPI_TalonFX(ARM_MC_ID);

        armMtr.configFactoryDefault();

        armMtr.setNeutralMode(NeutralMode.Brake);
        armMtr.configReverseLimitSwitchSource(LimitSwitchSource.FeedbackConnector, LimitSwitchNormal.NormallyOpen);
        armMtr.overrideLimitSwitchesEnable(LIMIT_SWITCH_MONITORED);

        armMtr.config_kP(0, ARM_KP);
        armMtr.config_kI(0, ARM_KI);
        armMtr.config_kD(0, ARM_KD);

        armCurrentLimit = new SupplyCurrentLimitConfiguration(ENABLE_CURRENT_LIMIT, CURRENT_LIMIT_AMPS, CURRENT_LIMIT_TRIGGER_AMPS, CURRENT_LIMIT_TIMEOUT_SECONDS);

        armMtr.configSupplyCurrentLimit(armCurrentLimit);

        armMtr.configAllowableClosedloopError(0, ARM_CLOSELOOP_ERROR);

        armMtr.set(ControlMode.PercentOutput, MANUAL_CONTROL_PWR_OFF);

        armMtr.configOpenloopRamp(1.0);

        startArmThread();

    }


    /*-----------------------------------------------------------------------------------------
    *  
    *  cmdProcArm()
    *
    *----------------------------------------------------------------------------------------*/
    public void cmdProcArm(boolean armExtend, boolean armRetract,
                            int cmdUpdateState)
    {
       
    if(cmdUpdateState != Robot.COMMAND_STATE_NULL)
    {
        Robot.armControlMode = Robot.mechMode.AutoMode;
        switch(cmdUpdateState)
        {
            case Robot.COMMAND_UPDATE_PICKUP_GROUND_CONE :    
            case Robot.COMMAND_UPDATE_PICKUP_GROUND_CUBE : 
            case Robot.COMMAND_UPDATE_PICKUP_SINGLE_CUBE :
            case Robot.COMMAND_UPDATE_SCORE_LOW_CONE:
            case Robot.COMMAND_UPDATE_SCORE_LOW_CUBE:
                highExtendProcess = false;
                armMovementMode   = Robot.MODE_AUTO;
                armMtr.set(ControlMode.Position, POS_ENC_CNTS_PICKUP);
                armInPosition = false;
                targetPosition = POS_ENC_CNTS_PICKUP;

                
            break;

            case Robot.COMMAND_UPDATE_SCORE_HIGH_CONE:
            case Robot.COMMAND_UPDATE_SCORE_HIGH_CUBE:
                highExtendProcess = true;
                armMovementMode   = Robot.MODE_AUTO;
                armInPosition = false;
                targetPosition = POS_ENC_CNTS_EXTEND;
            break;

            case Robot.COMMAND_UPDATE_STOW           :
            case Robot.COMMAND_UPDATE_PICKUP_SINGLE_CONE :
            case Robot.COMMAND_UPDATE_SCORE_MID_CUBE :
            case Robot.COMMAND_UPDATE_SCORE_MID_CONE :
                highExtendProcess = false;
                armMovementMode   = Robot.MODE_AUTO;
                armMtr.set(ControlMode.Position, POS_ENC_CNTS_RETRACT);
                armInPosition = false;
                targetPosition = POS_ENC_CNTS_RETRACT;
            break;
        }
    }

        if(armExtend == true)
        {
            armMovementMode = Robot.MODE_MANUAL;
            Robot.armControlMode = mechMode.ManualMode;

            setArmPwr(EXTEND_PWR);

          
            highExtendProcess = false;
            
        }
        else if(armRetract == true)
        {
            armMovementMode = Robot.MODE_MANUAL;
            Robot.armControlMode = mechMode.ManualMode;

            setArmPwr(RETRACT_PWR);
          
            highExtendProcess = false;

            
        }
        else if(armMtr.getControlMode() == ControlMode.PercentOutput)
        {
            setArmPwr(MANUAL_CONTROL_PWR_OFF);
        }
        
    }   //End of cmdProcArm()


    /*-----------------------------------------------------------------------------------------
    *  
    *  Arm Thread
    *
    *----------------------------------------------------------------------------------------*/
    public void startArmThread()
    {
        Thread armThread = new Thread(() ->
        {
            while(true)
            {
                if(highExtendProcess == true)
                {
                    elevatorPosition = Robot.elevator.getElevatorEncoder();

                    if(DriverStation.isAutonomousEnabled() && Robot.selectedGamePiece == Robot.GP_CONE)
                    {

                        if(elevatorPosition >= POS_ENC_CNTS_HIGH_EXTEND_THRESHOLD_ELEVATOR && 
                           Robot.intake.isIntakeInPos())
                        {
                            armMtr.set(ControlMode.Position, POS_ENC_CNTS_EXTEND);
                            highExtendProcess = false;
                        }
                    }
                    else
                    {
                        if(elevatorPosition >= POS_ENC_CNTS_HIGH_EXTEND_THRESHOLD_ELEVATOR)
                        {
                            armMtr.set(ControlMode.Position, POS_ENC_CNTS_EXTEND);
                            highExtendProcess = false; 
                        }
                    }
                }

                currentPosition = armMtr.getSelectedSensorPosition();
                positionError = currentPosition - targetPosition;
                if  ((Math.abs(positionError) <= ERROR_ARM_THRESHOLD) && targetPosition != NO_TARGET_POSITION)
                {
                    targetPosition = NO_TARGET_POSITION;
                    numConsectSamples++;
                        if(numConsectSamples >= 10)
                        {   
                            armInPosition = true;
                        }
                }
                else
                {
                    numConsectSamples = 0;
                }
                

                if((DataCollection.chosenDataID.getSelected() == DataCollection.LOG_ID_ARM)) 
                {        
                    data = new CatzLog(Robot.currentTime.get(), targetPosition, currentPosition, 
                                                                positionError, 
                                                                armMtr.getMotorOutputPercent(),
                                                                            -999.0, -999.0, -999.0, -999.0, -999.0,
                                                                            -999.0, -999.0, -999.0, -999.0, -999.0,
                                                                            DataCollection.boolData);
                                            
                    Robot.dataCollection.logData.add(data);
                }

                Timer.delay(0.02);
            }   //End of while(true)
        });
        armThread.start();
    }



    /*-----------------------------------------------------------------------------------------
    *  
    *  xxx()
    *
    *----------------------------------------------------------------------------------------*/
    public void checkLimitSwitches()
    {
        if(armMtr.getSensorCollection().isRevLimitSwitchClosed() == SWITCH_CLOSED)
        {
            armMtr.setSelectedSensorPosition(POS_ENC_CNTS_RETRACT);
            extendSwitchState = true;
        }
        else
        {
            extendSwitchState = false;
        }


    }

    public void setArmPwr(double pwr)
    {        
        armMtr.set(ControlMode.PercentOutput, pwr);
    }

    public double getArmEncoder()
    {
        return armMtr.getSelectedSensorPosition();
    }

    public int getArmMovementMode()
    {
        return armMovementMode;
    }

    public void smartDashboardARM()
    {
        SmartDashboard.putNumber("arm encoder position", armMtr.getSelectedSensorPosition());
    }
    public boolean isArmInPos()
    {
        return armInPosition;
    }
}
