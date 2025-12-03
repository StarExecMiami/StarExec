// Navigation highlighting on scroll
document.addEventListener('DOMContentLoaded', function() {
	const navLinks = document.querySelectorAll('.nav-link');
	const sections = document.querySelectorAll('.content-section');
	
	const observer = new IntersectionObserver((entries) => {
		entries.forEach(entry => {
			if (entry.isIntersecting) {
				const id = entry.target.getAttribute('id');
				navLinks.forEach(link => {
					link.classList.remove('active');
					if (link.getAttribute('href') === `#${id}`) {
						link.classList.add('active');
					}
				});
			}
		});
	}, { threshold: 0.3 });
	
	sections.forEach(section => observer.observe(section));
	
	// Smooth scroll
	navLinks.forEach(link => {
		link.addEventListener('click', function(e) {
			e.preventDefault();
			const href = this.getAttribute('href');
			if (href && href.startsWith('#')) {
				const targetId = href.substring(1);
				const target = document.getElementById(targetId);
				if (target) {
					target.scrollIntoView({
						behavior: 'smooth',
						block: 'start'
					});
				}
			}
		});
	});
	
	// Scroll to top
	const scrollToTopBtn = document.querySelector('.scroll-to-top');
	if (scrollToTopBtn) {
		scrollToTopBtn.addEventListener('click', (e) => {
			e.preventDefault();
			window.scrollTo({ top: 0, behavior: 'smooth' });
		});
	}
});
