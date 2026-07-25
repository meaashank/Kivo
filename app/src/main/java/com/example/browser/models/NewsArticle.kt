package com.example.browser.models

data class NewsArticle(
    val id: String,
    val title: String,
    val source: String,
    val timeAgo: String,
    val category: String,
    val url: String,
    val imageUrl: String,
    val snippet: String
)

object NewsRepository {
    fun getArticles(category: String = "All", language: String = "English", region: String = "United States"): List<NewsArticle> {
        val allArticles = listOf(
            NewsArticle(
                id = "1",
                title = "Next-Gen Quantum Computing Chips Break Coherence Records",
                source = "TechCrunch",
                timeAgo = "18m ago",
                category = "Tech",
                url = "https://news.google.com",
                imageUrl = "https://images.unsplash.com/photo-1635070041078-e363dbe005cb?w=600&auto=format&fit=crop",
                snippet = "Engineers achieve 99.9% gate fidelity operating at room temperatures."
            ),
            NewsArticle(
                id = "2",
                title = "Global Renewable Energy Generation Surpasses Coal for First Time",
                source = "BBC News",
                timeAgo = "1h ago",
                category = "World",
                url = "https://news.google.com",
                imageUrl = "https://images.unsplash.com/photo-1466611653911-95081537e5b7?w=600&auto=format&fit=crop",
                snippet = "Solar and wind expansion speeds up across North America and Europe."
            ),
            NewsArticle(
                id = "3",
                title = "James Webb Telescope Discovers Atmospheric Water on Earth-Sized Exoplanet",
                source = "NASA Science",
                timeAgo = "2h ago",
                category = "Tech",
                url = "https://news.google.com",
                imageUrl = "https://images.unsplash.com/photo-1451187580459-43490279c0fa?w=600&auto=format&fit=crop",
                snippet = "Spectroscopic analysis reveals water vapor signatures in habited orbit zone."
            ),
            NewsArticle(
                id = "4",
                title = "Global Markets Rally Following Central Bank Interest Rate Cut",
                source = "Bloomberg",
                timeAgo = "3h ago",
                category = "Business",
                url = "https://news.google.com",
                imageUrl = "https://images.unsplash.com/photo-1611974789855-9c2a0a7236a3?w=600&auto=format&fit=crop",
                snippet = "Tech indices surge to record highs as borrowing costs decline worldwide."
            ),
            NewsArticle(
                id = "5",
                title = "Revolutionary Solid-State Batteries Enter Commercial Electric Vehicle Trials",
                source = "Reuters",
                timeAgo = "4h ago",
                category = "Tech",
                url = "https://news.google.com",
                imageUrl = "https://images.unsplash.com/photo-1558441719-677975871804?w=600&auto=format&fit=crop",
                snippet = "1,000 km range achieved with 10-minute fast charging capability."
            ),
            NewsArticle(
                id = "6",
                title = "Champions League Finals: Underdog Squad Secures Stunning Victory",
                source = "ESPN Sports",
                timeAgo = "5h ago",
                category = "Sports",
                url = "https://news.google.com",
                imageUrl = "https://images.unsplash.com/photo-1508098682722-e99c43a406b2?w=600&auto=format&fit=crop",
                snippet = "Late stoppage time goal seals dramatic comeback in front of 80,000 fans."
            ),
            NewsArticle(
                id = "7",
                title = "DeepMind Unveils New Medical AI Capable of Early Cancer Biomarker Detection",
                source = "MIT Technology Review",
                timeAgo = "6h ago",
                category = "Tech",
                url = "https://news.google.com",
                imageUrl = "https://images.unsplash.com/photo-1532187863486-abf9dbad1b69?w=600&auto=format&fit=crop",
                snippet = "Clinical trials show 97% accuracy in identifying early-stage cellular anomalies."
            ),
            NewsArticle(
                id = "8",
                title = "Historic Space Summit Agrees on New Lunar Exploration Accord",
                source = "Associated Press",
                timeAgo = "7h ago",
                category = "World",
                url = "https://news.google.com",
                imageUrl = "https://images.unsplash.com/photo-1517976487492-5750f3195933?w=600&auto=format&fit=crop",
                snippet = "Thirty nations sign safety guidelines for permanent lunar surface habitats."
            )
        )

        return if (category == "All") allArticles else allArticles.filter { it.category.equals(category, ignoreCase = true) }
    }
}
