package com.systemdesign.yelp.config;

import com.systemdesign.yelp.dto.AddBusinessRequest;
import com.systemdesign.yelp.dto.AddReviewRequest;
import com.systemdesign.yelp.model.Business;
import com.systemdesign.yelp.model.User;
import com.systemdesign.yelp.repository.UserRepository;
import com.systemdesign.yelp.service.BusinessService;
import com.systemdesign.yelp.service.ReviewService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Loads sample data on startup (active by default for local dev).
 * San Francisco businesses and users for easy curl/Swagger testing.
 */
@Component
@Profile("!test")
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final BusinessService businessService;
    private final ReviewService reviewService;
    private final UserRepository userRepository;

    public DataInitializer(BusinessService businessService, ReviewService reviewService,
                           UserRepository userRepository) {
        this.businessService = businessService;
        this.reviewService = reviewService;
        this.userRepository = userRepository;
    }

    @PostConstruct
    public void init() {
        // Create sample users
        User alice = createUser("alice@example.com", "Alice Chen");
        User bob = createUser("bob@example.com", "Bob Martinez");
        User carol = createUser("carol@example.com", "Carol Kim");

        // San Francisco — Union Square area (37.7879, -122.4074)
        Business pizza = addBusiness("Tony's Coal-Fired Pizza", "1556 Stockton St", "San Francisco",
            "CA", "94133", "USA", 37.8006, -122.4109, "Pizza", "Award-winning Neapolitan pizza",
            "$$$", 2, alice.getId());

        Business ramen = addBusiness("Mensho Tokyo", "672 Geary St", "San Francisco",
            "CA", "94102", "USA", 37.7867, -122.4168, "Ramen", "Japanese ramen with rich broth",
            "$$", 2, alice.getId());

        Business coffee = addBusiness("Blue Bottle Coffee", "66 Mint St", "San Francisco",
            "CA", "94103", "USA", 37.7824, -122.4069, "Coffee", "Specialty single-origin coffee",
            "$$", 1, bob.getId());

        Business tacos = addBusiness("La Taqueria", "2889 Mission St", "San Francisco",
            "CA", "94110", "USA", 37.7511, -122.4186, "Mexican", "Mission-style burritos and tacos",
            "$", 1, carol.getId());

        Business sushi = addBusiness("Omakase", "45 Belden Pl", "San Francisco",
            "CA", "94104", "USA", 37.7908, -122.4034, "Sushi", "Michelin-starred omakase experience",
            "$$$$", 4, alice.getId());

        // Reviews
        addReview(pizza.getId(), alice.getId(), 5, "Best pizza in the city. The Margherita is perfect.");
        addReview(pizza.getId(), bob.getId(), 4, "Great flavor, long wait but worth it.");
        addReview(pizza.getId(), carol.getId(), 5, "Coal-fired perfection. Will return.");

        addReview(ramen.getId(), bob.getId(), 5, "Mind-blowing broth. Waited 45 min and zero regrets.");
        addReview(ramen.getId(), carol.getId(), 4, "Rich and complex. The mazesoba is incredible.");

        addReview(coffee.getId(), alice.getId(), 4, "Clean, bright coffee. Excellent pour-over.");
        addReview(coffee.getId(), carol.getId(), 5, "My go-to for single origin beans.");

        addReview(tacos.getId(), alice.getId(), 5, "The OG Mission burrito. Super burrito is legendary.");
        addReview(tacos.getId(), bob.getId(), 5, "Best tacos I've had anywhere. Simple, authentic.");

        addReview(sushi.getId(), bob.getId(), 5, "A transcendent dining experience. Worth every penny.");

        log.info("Sample data initialized: {} businesses", 5);
    }

    private User createUser(String email, String name) {
        User u = new User();
        u.setEmail(email);
        u.setName(name);
        return userRepository.save(u);
    }

    private Business addBusiness(String name, String address, String city, String state,
                                  String zip, String country, double lat, double lon,
                                  String category, String description, String priceDisplay,
                                  int priceRange, String ownerId) {
        AddBusinessRequest req = new AddBusinessRequest();
        req.setName(name);
        req.setAddress(address);
        req.setCity(city);
        req.setState(state);
        req.setZipCode(zip);
        req.setCountry(country);
        req.setLatitude(lat);
        req.setLongitude(lon);
        req.setCategory(category);
        req.setDescription(description);
        req.setPriceRange(priceRange);
        req.setOwnerId(ownerId);
        req.setTags(List.of(category.toLowerCase(), city.toLowerCase()));
        return businessService.addBusiness(req);
    }

    private void addReview(String businessId, String userId, int stars, String text) {
        try {
            AddReviewRequest req = new AddReviewRequest();
            req.setBusinessId(businessId);
            req.setUserId(userId);
            req.setStars(stars);
            req.setText(text);
            reviewService.addReview(req);
        } catch (Exception e) {
            log.warn("Could not add sample review: {}", e.getMessage());
        }
    }
}
