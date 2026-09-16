package dev.andreymudri.villagercity.citizen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.item.ItemStack;

/**
 * Saved per-villager state. The tool is the canonical stack: the scheduler keeps it in the main hand,
 * because vanilla trading overwrites the main hand to show trade items.
 */
public final class CitizenData {
    public static final Codec<CitizenData> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.optionalFieldOf("village").forGetter(d -> Optional.ofNullable(d.villageId)),
            JobType.CODEC.optionalFieldOf("job", JobType.NONE).forGetter(CitizenData::job),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("tool", ItemStack.EMPTY).forGetter(CitizenData::tool)
    ).apply(i, (village, job, tool) -> new CitizenData(village.orElse(null), job, tool)));

    private @Nullable UUID villageId;
    private JobType job;
    private ItemStack tool;

    public CitizenData() {
        this(null, JobType.NONE, ItemStack.EMPTY);
    }

    public CitizenData(@Nullable UUID villageId, JobType job, ItemStack tool) {
        this.villageId = villageId;
        this.job = job;
        this.tool = tool;
    }

    public @Nullable UUID villageId() {
        return villageId;
    }

    public JobType job() {
        return job;
    }

    public ItemStack tool() {
        return tool;
    }

    public void clear() {
        villageId = null;
        job = JobType.NONE;
        tool = ItemStack.EMPTY;
    }
}
