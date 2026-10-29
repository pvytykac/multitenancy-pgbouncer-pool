CHART := ./helm
RELEASE := demo
NAMESPACE := default

build-images:
	docker build -t backend-dummy:latest ./backend-dummy
	docker build -t provisioner:latest ./provisioner
	docker build -t frontend:latest ./frontend

load-images:
	kind load docker-image backend-dummy:latest provisioner:latest frontend:latest

helm-lint:
	helm lint $(CHART)

helm-template:
	helm template $(RELEASE) $(CHART)

helm-install:
	helm upgrade --install $(RELEASE) $(CHART) -n $(NAMESPACE) --create-namespace

helm-uninstall:
	helm uninstall $(RELEASE) -n $(NAMESPACE)

seed:
	bash load-test/seed.sh

load-test:
	bash load-test/run.sh

.PHONY: build-images load-images helm-lint helm-template helm-install helm-uninstall seed load-test
