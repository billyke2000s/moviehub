package local.tv.home;

public final class LiveRepairBudgetTest {

    private static int checks;

    public static void main(String[] args) {
        LiveRepairBudget budget = new LiveRepairBudget();
        check(budget.allowed());
        budget.observe(20, true);
        budget.repaired();
        check(!budget.allowed());
        // Neither old queued frames nor the surface disappearing establishes recovery.
        budget.observe(22, false);
        check(!budget.allowed());
        budget.observe(22, true);
        check(!budget.allowed());
        for (int i = 0; i < 20; i++) {
            budget.observe(22, true);
            check(!budget.allowed());
        }
        budget.observe(23, true);
        check(budget.allowed());
        budget.repaired();
        budget.observe(-1, true);
        check(!budget.allowed());
        budget.observe(1, true);
        check(!budget.allowed());
        budget.observe(2, true);
        check(budget.allowed());
        budget.repaired();
        budget.observe(2, false);
        budget.observe(3, true);
        check(!budget.allowed());
        budget.observe(3, true);
        check(!budget.allowed());
        budget.reset();
        check(budget.allowed());
        System.out.println(checks + " bounded surface repair checks passed");
    }

    private static void check(boolean ok) {
        checks++;
        if (!ok) throw new AssertionError("Repair budget check " + checks);
    }
}
