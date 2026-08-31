package orbiter.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import orbiter.modules.ClientSideThings;
import orbiter.util.ClientSpoofState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public abstract class WorldSkySpoofMixin {
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void orbiter$applySky(ClientLevel level, float partialTicks, Camera camera, SkyRenderState skyRenderState, CallbackInfo ci) {
        ClientSideThings module = ClientSpoofState.module();
        if (module == null || !module.shouldSpoofSky()) return;

        skyRenderState.skybox = switch (module.getSkyMode()) {
            case End -> DimensionType.Skybox.END;
            case Nether -> DimensionType.Skybox.NONE;
            case Overworld -> DimensionType.Skybox.OVERWORLD;
        };
    }
}
