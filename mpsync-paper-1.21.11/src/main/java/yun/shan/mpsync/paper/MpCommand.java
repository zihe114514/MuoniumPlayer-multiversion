package yun.shan.mpsync.paper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import yun.shan.mpsync.MpParty;
import yun.shan.mpsync.command.MpTextCommands;

import java.util.List;
import java.util.UUID;

/**
 * Thin Bukkit command wrapper around {@link MpTextCommands}.
 *
 * <p>All room-state mutation is funnelled through {@link PaperPlatform#execute}, i.e. the
 * global region scheduler on Folia, so command handling is serialised with the tick and
 * packet paths. Feedback lines are delivered back to the actor through
 * {@link PaperPlatform#chat}, which hops onto the player's own entity scheduler — the only
 * thread allowed to touch that player.
 */
public final class MpCommand implements CommandExecutor, TabCompleter {

    private final PaperPlatform platform;

    public MpCommand(PaperPlatform platform) {
        this.platform = platform;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("一起听房间只能由玩家操作");
            return true;
        }
        UUID uuid = player.getUniqueId();
        boolean operator = sender.isOp() || sender.hasPermission("mpsync.admin");
        platform.execute(() -> {
            List<String> lines = MpTextCommands.run(uuid, operator, args);
            for (String line : lines) platform.chat(uuid, line);
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return MpTextCommands.suggest(args, platform.onlineNames());
    }
}
