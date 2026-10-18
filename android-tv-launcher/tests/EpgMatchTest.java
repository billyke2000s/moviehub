package local.tv.home;

public class EpgMatchTest {

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        check(EpgMatch.key("BBC One (1080p)").equals("bbcone"), "name with quality");
        check(EpgMatch.key("BBCOne.uk@HD").equals("bbcone"), "iptv-org id");
        check(EpgMatch.key("BBC.One.HD.uk").equals("bbcone"), "guide id");
        check(EpgMatch.key("BBC One HD").equals("bbcone"), "guide display name");
        check(EpgMatch.key("5 USA").equals("5usa"), "numbers kept");
        check(EpgMatch.key("ITV1 London").equals("itv1london"), "region kept");
        check(EpgMatch.country("Gold.it@SD").equals("it"), "country from id");
        check(EpgMatch.keys("Gold.it", "Gold", "uk").isEmpty(), "other countries are not matched");
        check(EpgMatch.keys("Gold.uk", "Gold (720p)", "uk").contains("gold"), "same country matched");
        check(EpgMatch.keys("", "Dave", "uk").contains("dave"), "no id: matched by name");
        System.out.println("10 programme-guide matching checks passed");
    }
}
