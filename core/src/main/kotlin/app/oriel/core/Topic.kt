package app.oriel.core

/** YouTube's topic channels, which stand in for the signed-out home. */
enum class Topic(val title: String, val browseId: String) {
    MUSIC("Music", "UC-9-kyTW8ZkZNDHQJ6FgpwQ"),
    GAMING("Gaming", "UCOpNcN46UbXVtpKMrmU4Abg"),
    NEWS("News", "UCYfdidRxbB8Qhf0Nx7ioOYw"),
    SPORTS("Sports", "UCEgdi0XIXXZ-qJOFPf4JSKw"),
    LEARNING("Learning", "UCtFRv9O2AHqOZjjynzrv-xg"),
    LIVE("Live", "UC4R8DWoMoI7CAwX8_LjQHig"),
    FASHION("Fashion & Beauty", "UCrpQ4p1Ql_hG8rKXIKM1MOQ"),
}
