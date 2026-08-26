package me.skepi.skepifb.schematic;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.session.ClipboardHolder;
import me.skepi.skepifb.SkepiFBPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;

public class SchematicService {

    private final SkepiFBPlugin plugin;
    private final boolean worldEditAvailable;

    public SchematicService(SkepiFBPlugin plugin) {
        this.plugin = plugin;
        this.worldEditAvailable = detectWorldEdit();
    }

    private boolean detectWorldEdit() {
        Plugin found = Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (found == null) {
            plugin.getLogger().warning("WorldEdit is not available. Schematic functionality will be disabled.");
            return false;
        }
        return found instanceof com.sk89q.worldedit.bukkit.WorldEditPlugin;
    }

    public boolean isWorldEditAvailable() {
        return worldEditAvailable;
    }

    public boolean schematicExists(String schematicName) {
        File schematicFile = getSchematicFile(schematicName);
        return schematicFile.exists();
    }

    public boolean pasteSchematic(String schematicName, Location location) {
        if (!isWorldEditAvailable()) {
            return false;
        }

        File schematicFile = getSchematicFile(schematicName);
        if (!schematicFile.exists()) {
            plugin.getLogger().warning("Could not paste schematic because file was missing: " + schematicName);
            return false;
        }

        com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat format = ClipboardFormats.findByFile(schematicFile);
        if (format == null) {
            plugin.getLogger().warning("Unsupported schematic format: " + schematicName);
            return false;
        }

        FileInputStream inputStream = null;
        ClipboardReader reader = null;
        com.sk89q.worldedit.EditSession editSession = null;
        try {
            inputStream = new FileInputStream(schematicFile);
            reader = format.getReader(inputStream);
            com.sk89q.worldedit.extent.clipboard.Clipboard clipboard = reader.read();
            editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(location.getWorld()));

            ClipboardHolder holder = new ClipboardHolder(clipboard);
            Operation operation = holder.createPaste(editSession)
                    .to(BukkitAdapter.asBlockVector(location))
                    .ignoreAirBlocks(false)
                    .build();

            Operations.complete(operation);
            return true;
        } catch (IOException | WorldEditException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to paste schematic: " + schematicName, ex);
            return false;
        } finally {
            if (editSession != null) {
                editSession.close();
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private File getSchematicFile(String schematicName) {
        Path schematicsPath = plugin.getDataFolder().toPath().getParent().resolve("WorldEdit").resolve("schematics");
        return schematicsPath.resolve(schematicName).toFile();
    }
}
