package com.nova5bike.dashboard;

public final class MediaMetadataTest {
    public static void main(String[] args) {
        AppleBle.Media media = new AppleBle.Media();
        media.update(2, 0, "歌手", 0);
        media.update(2, 2, "截短的", 1);
        media.markIncomplete(2, 2, true);
        check(media.title.equals("截短的") && media.titleForLookup().isEmpty(),
                "A partial title can be displayed but not queried");
        media.update(2, 2, "截短的歌名已经补全", 2);
        media.markIncomplete(2, 2, false);
        media.markIncomplete(2, 1, true);
        check(media.titleForLookup().equals("截短的歌名已经补全") && media.artistForLookup().equals("歌手")
                && media.albumForLookup().isEmpty(), "A pending album must not block a full recording query");
        check(media.matchesArtworkAlbum(null), "A pending album does not block supplied artwork");
        media.update(2, 1, "Studio", 3);
        media.markIncomplete(2, 1, false);
        check(media.matchesArtworkAlbum("studio") && !media.matchesArtworkAlbum("Live")
                && !media.matchesArtworkAlbum(null), "Supplied artwork must match a known album");
        media.markIncomplete(2, 0, true);
        check(media.artistForLookup().isEmpty(), "Partial artist names must not be queried");
        System.out.println("Bluetooth partial and complete music metadata: passed");
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
