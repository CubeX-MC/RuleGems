package me.hushu;

import java.util.List;

public class ExecuteConfig {
    private List<String> commands;
    private String sound;
    private String particle;

    // 含参构造（可选，看你项目需要）
    public ExecuteConfig(List<String> commands, String sound, String particle) {
        this.commands = commands;
        this.sound = sound;
        this.particle = particle;
    }

    // Getter 和 Setter
    public List<String> getCommands() {
        return commands;
    }

    public String getSound() {
        return sound;
    }

    public String getParticle() {
        return particle;
    }
}
