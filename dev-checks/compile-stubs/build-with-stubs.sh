cd "$(dirname "$0")/../../src/main/java"
P=com/veyrion/character
rm -rf /tmp/cls && mkdir -p /tmp/cls
javac -proc:none -Xmaxerrs 200 -d /tmp/cls $(find ../../../dev-checks/compile-stubs/paper-api ../../../dev-checks/compile-stubs/project -name '*.java') $P/awakening/*.java $P/quest/*.java $P/config/*.java $P/race/*.java $P/character/*.java $P/database/*.java $P/util/*.java $P/integration/PlaceholderResolver.java $P/integration/CharacterCache.java $P/integration/PopulationCache.java $P/economy/Money.java $P/economy/PendingVyrRepository.java 2>&1 | grep -v JAVA_TOOL
