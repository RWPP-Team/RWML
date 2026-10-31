package fixture.extension;

import fixture.game.FactoryBuildQueue;
import kim.der.asm.api.annotation.AsmMixin;

@AsmMixin("fixture/Target")
public final class FactoryAccessorFixture implements FactoryAccess {
    @Override
    public FactoryBuildQueue factoryBuildQueue() {
        return null;
    }
}
