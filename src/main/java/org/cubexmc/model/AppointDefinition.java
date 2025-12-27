package org.cubexmc.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 委任定义模型
 * 定义一个可以被委任的职位及其附带的权力
 */
public class AppointDefinition {

    private final String key;
    private String displayName;
    private String description;
    private int maxAppointments;
    private PowerStructure powerStructure;
    
    // 委任相关的额外配置
    private String appointSound;
    private String revokeSound;
    private List<String> onAppoint; // 委任时执行的指令等
    private List<String> onRevoke; // 撤销时执行的指令等

    public AppointDefinition(String key) {
        this.key = key;
        this.displayName = key;
        this.description = "";
        this.maxAppointments = -1; // 无限制
        this.powerStructure = new PowerStructure();
        this.appointSound = "ENTITY_PLAYER_LEVELUP";
        this.revokeSound = "ENTITY_ITEM_BREAK";
        this.onAppoint = new ArrayList<>();
        this.onRevoke = new ArrayList<>();
    }

    public String getKey() {
        return key;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getMaxAppointments() {
        return maxAppointments;
    }

    public void setMaxAppointments(int maxAppointments) {
        this.maxAppointments = maxAppointments;
    }

    public PowerStructure getPowerStructure() {
        return powerStructure;
    }

    public void setPowerStructure(PowerStructure powerStructure) {
        this.powerStructure = powerStructure;
    }

    public String getAppointSound() {
        return appointSound;
    }

    public void setAppointSound(String appointSound) {
        this.appointSound = appointSound;
    }

    public String getRevokeSound() {
        return revokeSound;
    }

    public void setRevokeSound(String revokeSound) {
        this.revokeSound = revokeSound;
    }

    public List<String> getOnAppoint() {
        return onAppoint;
    }

    public void setOnAppoint(List<String> onAppoint) {
        this.onAppoint = onAppoint != null ? onAppoint : new ArrayList<>();
    }

    public List<String> getOnRevoke() {
        return onRevoke;
    }

    public void setOnRevoke(List<String> onRevoke) {
        this.onRevoke = onRevoke != null ? onRevoke : new ArrayList<>();
    }
}
