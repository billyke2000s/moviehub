package local.tv.home;

/** Experimental lookup session: one isolated heap per channel lookup, fresh globals per step. */
public final class FastProviderService extends ProviderService {

    @Override
    protected boolean reuseCompiledCode() {
        return true;
    }
}
