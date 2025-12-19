export class Debouncer {
    private timeout: NodeJS.Timeout | undefined;

    constructor(private defaultDelay: number = 300) {}

    public debounce(fn: () => void, delay?: number) {
        const wait = delay !== undefined ? delay : this.defaultDelay;
        
        if (this.timeout) {
            clearTimeout(this.timeout);
        }
        this.timeout = setTimeout(fn, wait);
    }

    public cancel() {
        if (this.timeout) {
            clearTimeout(this.timeout);
            this.timeout = undefined;
        }
    }
}
