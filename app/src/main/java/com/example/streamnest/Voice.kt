package com.example.streamnest

data class VoiceAnswer(val say: String, val title: String, val items: List<Meta>)

data class Mood(val words: List<String>, val genres: List<String>, val title: String, val reply: String)

/** Simple, offline understanding of moods and genres for the voice assistant. Edit freely. */
object Voice {
    val moods = listOf(
        Mood(listOf("happy", "good", "great", "cheerful", "joyful", "fun", "awesome", "fantastic", "amazing"),
            listOf("Comedy", "Family", "Animation"), "Feel-good picks", "Love that! Here are some feel-good picks to keep the smile going."),
        Mood(listOf("sad", "down", "low", "upset", "lonely", "depressed", "unhappy", "heartbroken"),
            listOf("Comedy", "Animation", "Family"), "Something uplifting", "Sorry you're feeling low. Here's something light and uplifting."),
        Mood(listOf("excited", "energetic", "pumped", "adventurous", "hyped"),
            listOf("Action", "Adventure"), "High-energy action", "Let's get the adrenaline going! Here's some action."),
        Mood(listOf("bored", "boring"),
            listOf("Thriller", "Adventure", "Mystery"), "Edge-of-your-seat picks", "Bored? These should keep you hooked."),
        Mood(listOf("scared", "spooky", "creepy", "brave"),
            listOf("Horror"), "Scary nights", "Lights off? Here are some scary ones."),
        Mood(listOf("romantic", "love", "loved", "date"),
            listOf("Romance"), "Romantic picks", "Aww. Here's something romantic."),
        Mood(listOf("tired", "relaxed", "chill", "calm", "lazy", "sleepy", "relax"),
            listOf("Animation", "Comedy", "Family"), "Easy watching", "Time to unwind. Here's something easy to relax with."),
        Mood(listOf("curious", "smart", "learn", "thoughtful", "inspired"),
            listOf("Documentary", "Sci-Fi", "History"), "Something to think about", "Curious mind! Here are some thoughtful picks."),
        Mood(listOf("angry", "stressed", "frustrated", "annoyed", "anxious"),
            listOf("Comedy", "Action"), "Blow off some steam", "Let's take your mind off things."),
        Mood(listOf("nostalgic", "old"),
            listOf("Family", "Adventure"), "Classic vibes", "Feeling nostalgic? Here are some classics."),
    )

    /** spoken word -> Cinemeta genre */
    val genres = linkedMapOf(
        "comedy" to "Comedy", "comedies" to "Comedy", "funny" to "Comedy",
        "action" to "Action", "horror" to "Horror", "romance" to "Romance", "romantic comedy" to "Romance",
        "thriller" to "Thriller", "thrillers" to "Thriller", "drama" to "Drama", "dramas" to "Drama",
        "animation" to "Animation", "animated" to "Animation", "cartoon" to "Animation", "cartoons" to "Animation", "anime" to "Animation",
        "sci-fi" to "Sci-Fi", "sci fi" to "Sci-Fi", "science fiction" to "Sci-Fi",
        "documentary" to "Documentary", "documentaries" to "Documentary",
        "crime" to "Crime", "fantasy" to "Fantasy", "mystery" to "Mystery", "family" to "Family", "kids" to "Family",
        "adventure" to "Adventure", "war" to "War", "musical" to "Musical", "western" to "Western", "history" to "History",
    )

    fun typeWord(types: List<String>) = when (types) { listOf("movie") -> "movies"; listOf("series") -> "series"; else -> "titles" }
}
