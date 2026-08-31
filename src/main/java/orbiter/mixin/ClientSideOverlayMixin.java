package orbiter.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import orbiter.modules.ClientSideThings;
import orbiter.util.ClientSpoofState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ScreenEffectRenderer.class)
public abstract class ClientSideOverlayMixin {
    @WrapOperation(
        method = "renderScreenEffect",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;renderFire(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V")
    )
    private void orbiter$renderFireOverlay(PoseStack matrices, MultiBufferSource bufferSource, TextureAtlasSprite sprite, Operation<Void> original) {
        ClientSideThings module = ClientSpoofState.module();

        if (module != null && module.shouldForceOffFireOverlay()) return;

        if (module != null && module.shouldForceFireOverlay()) {
            float height = (float) module.getFireOverlayHeight();
            if (height <= 0.0f) return;
            matrices.pushPose();
            matrices.scale(1.0f, height, 1.0f);
            original.call(matrices, bufferSource, sprite);
            matrices.popPose();
            return;
        }

        original.call(matrices, bufferSource, sprite);
    }

    @WrapOperation(
        method = "renderScreenEffect",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;renderWater(Lnet/minecraft/client/Minecraft;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V")
    )
    private void orbiter$renderUnderwaterOverlay(Minecraft client, PoseStack matrices, MultiBufferSource bufferSource, Operation<Void> original) {
        ClientSideThings module = ClientSpoofState.module();
        if (module != null && module.shouldForceOffWaterOverlay()) return;
        original.call(client, matrices, bufferSource);
    }
}
