package com.veyrion.character.quest;

import java.util.List;
import java.util.Map;

/** Which quest/objective/progress the quest scoreboard shows (QuestBoardView). Needs the Bukkit stubs only for enums. */
public final class QuestBoardCheck {
    static int fails = 0;
    static void check(boolean ok, String what) { if (!ok) { fails++; System.out.println("FAIL: " + what); } }

    static QuestObjective obj(String id, String text, int amount) {
        return new QuestObjective(id, ObjectiveType.values()[0], text, amount, null, null, null, null, 0, 0, 0, 0, null);
    }

    public static void main(String[] args) {
        Quest ruins = new Quest("ruins", "The Lost Relic", "d", QuestType.SIDE, 0, null, List.of(), 10, 0,
                List.of(obj("guardian", "Kalahkan Guardian", 5), obj("relic", "Ambil Relic", 1)), "Ancient Ruins");
        Quest plain = new Quest("plain", "Plain Quest", "d", QuestType.SIDE, 0, null, List.of(), 10, 0,
                List.of(obj("a", "Do a thing", 3)));
        Map<String, Quest> all = Map.of("ruins", ruins, "plain", plain);

        check(plain.area() == null, "old constructor has no area");
        check(QuestBoardView.of(null, all::get) == null, "no state -> no board");
        PlayerQuests none = new PlayerQuests();
        check(QuestBoardView.of(none, all::get) == null, "no active quest -> no board");

        PlayerQuests state = new PlayerQuests();
        state.addActive("ruins", 1000, Map.of("guardian", 3));
        QuestBoardView v = QuestBoardView.of(state, all::get);
        check(v != null && v.area().equals("Ancient Ruins"), "area from quest");
        check(v != null && v.objective().equals("Kalahkan Guardian"), "first unfinished objective");
        check(v != null && v.progress() == 3 && v.amount() == 5, "progress 3/5");

        // first objective done -> shows the second
        state.activeQuest("ruins").setProgress("guardian", 5);
        v = QuestBoardView.of(state, all::get);
        check(v.objective().equals("Ambil Relic") && v.progress() == 0 && v.amount() == 1, "next objective");

        // everything done -> still shows the last objective (quest is being completed)
        state.activeQuest("ruins").setProgress("relic", 1);
        v = QuestBoardView.of(state, all::get);
        check(v.objective().equals("Ambil Relic") && v.progress() == 1, "all done shows last");

        // progress above the amount is clamped
        state.activeQuest("ruins").setProgress("relic", 9);
        check(QuestBoardView.of(state, all::get).progress() == 1, "clamped");

        // two quests: the most recently accepted is tracked; area falls back to the quest name
        state.addActive("plain", 2000, Map.of());
        v = QuestBoardView.of(state, all::get);
        check(v.questId().equals("plain") && v.area().equals("Plain Quest"), "latest accepted quest wins, area = name");
        // after it is gone the older one shows again
        state.clear("plain");
        v = QuestBoardView.of(state, all::get);
        check(v != null && v.questId().equals("ruins"), "falls back to the other active quest");
        // unknown quest id (removed from files) is ignored
        PlayerQuests ghost = new PlayerQuests();
        ghost.addActive("deleted", 5, Map.of());
        check(QuestBoardView.of(ghost, all::get) == null, "unknown quest ignored");

        System.out.println(fails == 0 ? "ALL CHECKS PASSED" : fails + " CHECK(S) FAILED");
        if (fails != 0) System.exit(1);
    }
}
