#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p build/core-tests
javac -d build/core-tests android-tv-launcher/app/src/main/java/local/tv/home/{Playlist,Network,VideoFiles,EpisodeOrder,SourceRanking,ChannelQuery,EpgMatch,SkyNumbers,GuideOrder,SkipQueue,ErrorLog,SpeedTest,ChannelIdentity,ChannelCategory,ProviderFailure,LiveProgress,VodSourceFailover,PlaybackBuffer,LiveRepairBudget,LiveBuffering,LiveRestartBudget,LivePresentation,ColourPress}.java android-tv-launcher/tests/{PlaylistTest,NetworkTest,VideoFilesTest,ChannelQueryTest,EpgMatchTest,SkyNumbersTest,GuideOrderTest,SkipQueueTest,TroubleshootingTest,ChannelIdentityTest,ProviderFailureTest,LiveProgressTest,VodSourceFailoverTest,PlaybackBufferTest,LiveRepairBudgetTest,LiveBufferingTest,LiveRestartBudgetTest,LivePresentationTest,ColourPressTest}.java
for test in PlaylistTest NetworkTest VideoFilesTest ChannelQueryTest EpgMatchTest SkyNumbersTest GuideOrderTest SkipQueueTest TroubleshootingTest ChannelIdentityTest ProviderFailureTest LiveProgressTest VodSourceFailoverTest PlaybackBufferTest LiveRepairBudgetTest LiveBufferingTest LiveRestartBudgetTest LivePresentationTest ColourPressTest; do
    java -Xmx64m -cp build/core-tests local.tv.home.$test
done
