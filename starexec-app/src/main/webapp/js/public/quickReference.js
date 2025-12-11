// Simple navigation highlighting
document.addEventListener('DOMContentLoaded', function() {
	const navLinks = document.querySelectorAll('.nav-link');
	const sections = document.querySelectorAll('.content-section');
	
	// Intersection Observer for scroll highlighting
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
	}, { threshold: 0.5 });
	
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
	
	// Print functionality
	const printBtn = document.getElementById('printGuide');
	if (printBtn) {
		printBtn.addEventListener('click', () => {
			window.print();
		});
	}
});
