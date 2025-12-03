// Simple search functionality
document.addEventListener('DOMContentLoaded', function() {
	const searchInput = document.getElementById('helpSearch');
	const categoryCards = document.querySelectorAll('.category-card');
	
	if (searchInput) {
		searchInput.addEventListener('input', function(e) {
			const query = e.target.value.toLowerCase();
			
			categoryCards.forEach(card => {
				const title = card.querySelector('.link-card__title').textContent.toLowerCase();
				const description = card.querySelector('.link-card__description').textContent.toLowerCase();
				const links = Array.from(card.querySelectorAll('.topic-list a'))
					.map(a => a.textContent.toLowerCase())
					.join(' ');
				
				const matches = title.includes(query) || 
					description.includes(query) || 
					links.includes(query);
				
				card.classList.toggle('hidden', !matches && query.length > 0);
			});
		});
	}
});
